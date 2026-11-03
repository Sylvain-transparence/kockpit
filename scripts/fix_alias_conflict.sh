#!/bin/bash
# =============================================================================
# fix_alias_conflict.sh
# Fixes: invalid_alias_name_exception
# "An index exists with the same name as the alias"
#
# Usage:
#   ./fix_alias_conflict.sh <alias_name> [real_index_name]
#
# Examples:
#   ./fix_alias_conflict.sh 'platform-auditdata-dev-ttl30d-write'
#   ./fix_alias_conflict.sh 'platform-auditdata-dev-ttl30d-write' 'platform-auditdata-dev-ttl30d-2026.03.24-000001'
# =============================================================================

# --- Configuration -----------------------------------------------------------
OPENSEARCH_HOST="${OPENSEARCH_HOST:-https://localhost:9200}"
OPENSEARCH_USER="${OPENSEARCH_USER:-admin}"
OPENSEARCH_PASS="${OPENSEARCH_PASS:-admin}"

CURL="curl -sk -u ${OPENSEARCH_USER}:${OPENSEARCH_PASS}"

# --- Colors ------------------------------------------------------------------
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# --- Help --------------------------------------------------------------------
if [ "$1" = "-h" ] || [ "$1" = "--help" ] || [ -z "$1" ]; then
  echo -e "Usage: $0 <alias_name> [real_index_name]"
  echo -e "  alias_name      : The alias that conflicts with an existing index"
  echo -e "  real_index_name : (optional) The real index that should receive the alias"
  echo -e ""
  echo -e "  Env vars: OPENSEARCH_HOST, OPENSEARCH_USER, OPENSEARCH_PASS"
  echo -e ""
  echo -e "  Example:"
  echo -e "    $0 'platform-auditdata-dev-ttl30d-write'"
  echo -e "    $0 'platform-auditdata-dev-ttl30d-write' 'platform-auditdata-dev-ttl30d-2026.03.24-000001'"
  exit 0
fi

ALIAS_NAME="$1"
TARGET_INDEX="$2"   # optional

# =============================================================================
echo -e "\n${BOLD}${CYAN}======================================================${NC}"
echo -e "${BOLD}${CYAN}  fix_alias_conflict.sh${NC}"
echo -e "${BOLD}${CYAN}  Alias : ${ALIAS_NAME}${NC}"
echo -e "${BOLD}${CYAN}======================================================${NC}\n"

# =============================================================================
# STEP 1 — Confirm the conflict index exists
# =============================================================================
echo -e "${BOLD}[1/5] Checking if index '${ALIAS_NAME}' exists...${NC}"

HTTP_CODE=$($CURL -o /dev/null -w "%{http_code}" "${OPENSEARCH_HOST}/${ALIAS_NAME}")

if [ "$HTTP_CODE" != "200" ]; then
  echo -e "  ${GREEN}✓ No index found with name '${ALIAS_NAME}' (HTTP $HTTP_CODE)${NC}"
  echo -e "  ${YELLOW}→ The conflict may already be resolved, or the index name is different.${NC}\n"
  exit 0
fi

echo -e "  ${RED}✗ Index '${ALIAS_NAME}' exists — this is the conflict${NC}\n"

# =============================================================================
# STEP 2 — Check document count in the conflict index
# =============================================================================
echo -e "${BOLD}[2/5] Checking document count in conflict index...${NC}"

COUNT_RESP=$($CURL "${OPENSEARCH_HOST}/${ALIAS_NAME}/_count")
DOC_COUNT=$(echo "$COUNT_RESP" | jq -r '.count // 0')

echo -e "  → Document count: ${BOLD}${DOC_COUNT}${NC}\n"

# =============================================================================
# STEP 3 — Find the real target index (most recent matching pattern)
# =============================================================================
echo -e "${BOLD}[3/5] Finding the real write index...${NC}"

if [ -n "$TARGET_INDEX" ]; then
  echo -e "  → Using provided target index: ${BOLD}${TARGET_INDEX}${NC}"
else
  # Derive base pattern from alias (remove -write suffix)
  BASE_PATTERN="${ALIAS_NAME%-write}"
  echo -e "  → Searching for most recent index matching: ${BASE_PATTERN}-*"

  TARGET_INDEX=$($CURL "${OPENSEARCH_HOST}/_cat/indices/${BASE_PATTERN}-*?format=json&h=index,creation.date" | \
    jq -r 'sort_by(.["creation.date"]) | last | .index // empty')

  if [ -z "$TARGET_INDEX" ]; then
    echo -e "  ${RED}✗ Could not find a real index for pattern '${BASE_PATTERN}-*'${NC}"
    echo -e "  ${YELLOW}→ Please provide the target index as second argument${NC}"
    echo -e "  ${YELLOW}   $0 '${ALIAS_NAME}' '<your-real-index-name>'${NC}\n"
    exit 1
  fi

  echo -e "  ${GREEN}✓ Found: ${BOLD}${TARGET_INDEX}${NC}\n"
fi

# Verify target index exists
HTTP_TARGET=$($CURL -o /dev/null -w "%{http_code}" "${OPENSEARCH_HOST}/${TARGET_INDEX}")
if [ "$HTTP_TARGET" != "200" ]; then
  echo -e "  ${RED}✗ Target index '${TARGET_INDEX}' does not exist (HTTP $HTTP_TARGET)${NC}"
  exit 1
fi

# =============================================================================
# STEP 4 — Reindex if needed, then delete conflict index
# =============================================================================
echo -e "${BOLD}[4/5] Resolving conflict index...${NC}"

if [ "$DOC_COUNT" -gt 0 ]; then
  echo -e "  ${YELLOW}⚠ Index has ${DOC_COUNT} documents — reindexing to '${TARGET_INDEX}' first...${NC}"

  REINDEX_RESP=$($CURL -X POST "${OPENSEARCH_HOST}/_reindex" \
    -H 'Content-Type: application/json' \
    -d "{
      \"source\": { \"index\": \"${ALIAS_NAME}\" },
      \"dest\":   { \"index\": \"${TARGET_INDEX}\" }
    }")

  FAILURES=$(echo "$REINDEX_RESP" | jq -r '.failures | length')
  REINDEXED=$(echo "$REINDEX_RESP" | jq -r '.total // 0')

  if [ "$FAILURES" -gt 0 ]; then
    echo -e "  ${RED}✗ Reindex failed with ${FAILURES} errors:${NC}"
    echo "$REINDEX_RESP" | jq '.failures'
    echo -e "  ${RED}Aborting — conflict index NOT deleted${NC}"
    exit 1
  fi

  echo -e "  ${GREEN}✓ Reindexed ${REINDEXED} documents successfully${NC}"
else
  echo -e "  ${GREEN}✓ Index is empty — safe to delete directly${NC}"
fi

# Delete the conflict index
echo -e "  → Deleting conflict index '${ALIAS_NAME}'..."
DELETE_RESP=$($CURL -X DELETE "${OPENSEARCH_HOST}/${ALIAS_NAME}")
ACKNOWLEDGED=$(echo "$DELETE_RESP" | jq -r '.acknowledged // false')

if [ "$ACKNOWLEDGED" != "true" ]; then
  echo -e "  ${RED}✗ Failed to delete index:${NC}"
  echo "$DELETE_RESP" | jq .
  exit 1
fi

echo -e "  ${GREEN}✓ Conflict index deleted${NC}\n"

# =============================================================================
# STEP 5 — Create the alias on the real index
# =============================================================================
echo -e "${BOLD}[5/5] Creating alias '${ALIAS_NAME}' on '${TARGET_INDEX}'...${NC}"

# Check if alias already exists on target (maybe with is_write_index already)
EXISTING_ALIAS=$($CURL "${OPENSEARCH_HOST}/${TARGET_INDEX}/_alias/${ALIAS_NAME}" | jq -r 'keys[0] // empty' 2>/dev/null)

if [ -n "$EXISTING_ALIAS" ]; then
  echo -e "  ${YELLOW}⚠ Alias already exists on target — ensuring is_write_index: true${NC}"
fi

ALIAS_RESP=$($CURL -X POST "${OPENSEARCH_HOST}/_aliases" \
  -H 'Content-Type: application/json' \
  -d "{
    \"actions\": [
      {
        \"add\": {
          \"index\": \"${TARGET_INDEX}\",
          \"alias\": \"${ALIAS_NAME}\",
          \"is_write_index\": true
        }
      }
    ]
  }")

ALIAS_ACK=$(echo "$ALIAS_RESP" | jq -r '.acknowledged // false')

if [ "$ALIAS_ACK" != "true" ]; then
  echo -e "  ${RED}✗ Failed to create alias:${NC}"
  echo "$ALIAS_RESP" | jq .
  exit 1
fi

echo -e "  ${GREEN}✓ Alias created successfully${NC}\n"

# =============================================================================
# Final verification
# =============================================================================
echo -e "${BOLD}${CYAN}--- Final State ---${NC}"
FINAL=$($CURL "${OPENSEARCH_HOST}/${TARGET_INDEX}/_alias")
echo "$FINAL" | jq .

echo -e "\n${GREEN}${BOLD}✓ Fix complete!${NC}"
echo -e "  Alias  : ${CYAN}${ALIAS_NAME}${NC}"
echo -e "  Points to : ${CYAN}${TARGET_INDEX}${NC} (is_write_index: true)\n"
