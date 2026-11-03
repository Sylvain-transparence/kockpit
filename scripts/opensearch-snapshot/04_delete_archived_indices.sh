#!/usr/bin/env bash
# Step 4 - delete the indices that are entirely archived in the snapshot (whole-index DELETE:
# instant, and frees the disk immediately - unlike _delete_by_query).
#
# Refuses to delete anything unless:
#   - the snapshot is SUCCESS with 0 failed shards,
#   - every index to delete is in that snapshot,
# and, per index, skips it unless:
#   - it holds no alias as write index (would break ingestion),
#   - ALL its documents still match the archive filter (domain + before cutoff), i.e. nothing
#     was written to it after the snapshot.
#
#   ./04_delete_archived_indices.sh            # asks for confirmation
#   ./04_delete_archived_indices.sh --yes
#
# Env: HOST (default https://localhost:9200), REPO, SNAPSHOT, INDICES_FILE, DOMAIN, CUTOFF_MS
set -euo pipefail
cd "$(dirname "$0")"

HOST="${HOST:-https://localhost:9200}"
REPO="${REPO:-orders-archive}"
SNAPSHOT="${SNAPSHOT:-orders-before-20260930-1215}"
INDICES_FILE="${INDICES_FILE:-orders_20260930-1215_delete.txt}"
DOMAIN="${DOMAIN:-orders}"
CUTOFF_MS="${CUTOFF_MS:-1790763300000}"   # 2026-09-30 12:15 Europe/Paris
CURL=(curl -sk --fail-with-body -m 120 -H 'Content-Type: application/json')
QUERY="{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"domain\":\"${DOMAIN}\"}},{\"range\":{\"@timestamp\":{\"lt\":${CUTOFF_MS},\"format\":\"epoch_millis\"}}}]}}}"

count() {
  if [[ $# -gt 1 ]]; then "${CURL[@]}" "${HOST}/$1/_count" -d "$2"; else "${CURL[@]}" "${HOST}/$1/_count"; fi \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["count"])'
}

# No mapfile: macOS ships bash 3.2.
indices=()
while IFS= read -r line; do [[ -n "${line// }" ]] && indices+=("$line"); done < "$INDICES_FILE"
echo "➡️  Checking snapshot ${REPO}/${SNAPSHOT} covers the ${#indices[@]} indices in ${INDICES_FILE}"
snapshot_json=$("${CURL[@]}" "${HOST}/_snapshot/${REPO}/${SNAPSHOT}") \
  || { echo "❌ Cannot read snapshot ${REPO}/${SNAPSHOT} - nothing deleted: ${snapshot_json:-no response}"; exit 1; }
missing=$(SNAP="$snapshot_json" python3 - "${indices[@]}" <<'EOF'
import json, os, sys
s = json.loads(os.environ["SNAP"])["snapshots"][0]
if s["state"] != "SUCCESS" or s["shards"]["failed"]:
    sys.exit(f"❌ Snapshot state={s['state']}, failed shards={s['shards']['failed']} - nothing deleted.")
print(" ".join(i for i in sys.argv[1:] if i not in set(s["indices"])))
EOF
)
if [[ -n "$missing" ]]; then
  echo "❌ Not in the snapshot, nothing deleted: $missing"; exit 1
fi
echo "   ✅ snapshot SUCCESS, all ${#indices[@]} indices included"

# An index is an alias's write target if it's flagged is_write_index:true, OR if it's the alias's
# only index and not explicitly flagged false - OpenSearch then routes writes to it implicitly.
aliases_json=$("${CURL[@]}" "${HOST}/_alias")
write_indices=$(ALIASES="$aliases_json" python3 <<'EOF'
import json, os
from collections import defaultdict
members = defaultdict(list)
for index, a in json.loads(os.environ["ALIASES"]).items():
    for alias, conf in a.get("aliases", {}).items():
        members[alias].append((index, conf.get("is_write_index")))
targets = set()
for alias, idx in members.items():
    targets |= {i for i, w in idx if w is True}
    if len(idx) == 1 and idx[0][1] is not False:
        targets.add(idx[0][0])
print(" ".join(sorted(targets)))
EOF
)

to_delete=()
for i in "${indices[@]}"; do
  if [[ " $write_indices " == *" $i "* ]]; then echo "   ⚠️  skip $i: it is a write index"; continue; fi
  all=$(count "$i"); matching=$(count "$i" "$QUERY")
  if [[ "$all" != "$matching" ]]; then echo "   ⚠️  skip $i: $all docs but only $matching match the filter"; continue; fi
  to_delete+=("$i")
done

echo
# Checked before any "${to_delete[@]}" expansion: bash 3.2 + set -u treats an empty array as unbound.
if [[ ${#to_delete[@]} -eq 0 ]]; then echo "Nothing to delete."; exit 0; fi
echo "${#to_delete[@]} indices will be DELETED from ${HOST}:"
printf '   %s\n' "${to_delete[@]}"
if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "Type DELETE to confirm: " answer || { echo "No terminal - nothing deleted (use --yes)."; exit 1; }
  [[ "$answer" == "DELETE" ]] || { echo "Aborted - nothing deleted."; exit 1; }
fi

for i in "${to_delete[@]}"; do
  "${CURL[@]}" -X DELETE "${HOST}/${i}" >/dev/null && echo "   🗑️  $i"
done
echo
echo "✅ Done. If 00_plan.py listed PARTIAL indices, now run the step 5 command it printed"
echo "   (delete only their matching documents)."
