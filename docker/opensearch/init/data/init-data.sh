#!/bin/bash
# Loads the sample audits (domain kockpit, env local) into OpenSearch.
#
# The audits go to a concrete "<domain>-<index>-<env>-sample" index, attached to the "<domain>-<index>-<env>-read"
# alias the backend searches. The read alias itself is left to the audit stream, which attaches its own indices to it:
# creating a concrete index with the alias name would block it.
#
# INDEX_NAME must match the backend's INDEX_NAME: auditdata (kafka/, filesystem/) or audit-data (azure/), e.g.
#   docker exec -w /init-data -e INDEX_NAME=audit-data opensearch bash init-data.sh
set -e

OPENSEARCH=${OPENSEARCH:-http://localhost:9200}
DOMAIN=${DOMAIN:-kockpit}
INDEX_NAME=${INDEX_NAME:-auditdata}
ENV=${ENV:-local}

sample_index="${DOMAIN}-${INDEX_NAME}-${ENV}-sample"
read_alias="${DOMAIN}-${INDEX_NAME}-${ENV}-read"

echo "Waiting for OpenSearch to start..."
until curl -s "$OPENSEARCH" >/dev/null; do
  sleep 5
done

echo "Creating index $sample_index ..."
curl -sf -X PUT "$OPENSEARCH/$sample_index" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"aliases\": {\"$read_alias\": {}}}"
echo

curl -sf -X PUT "$OPENSEARCH/$sample_index/_mapping" \
    -H 'Content-Type: application/json' \
    --data-binary @index-template.json
echo

echo "Loading test data into $sample_index (read alias $read_alias)..."
curl -sf -X POST "$OPENSEARCH/$sample_index/_bulk?refresh=true&filter_path=errors,took" \
     -H 'Content-Type: application/json' \
     --data-binary @audits.json
echo

echo "test data loaded successfully"
