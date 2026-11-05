#!/bin/bash
# Migrates ONE existing OpenSearch node (mount point, heap, systemd unit, optional VM resize), from a
# workstation with `az` logged in and the cluster reachable (tunnel). Run it node after node, and
# only start the next one once this one reports the cluster green.
#
#   rolling-migrate.sh --rg kockpit-pro --vm opensearch-2-pro --heap 4g [--size Standard_B2ms]
#                      [--subscription <id or name>] [--url http://localhost:9200] [--data-path /data/opensearch]
#
# Try it on dev first (another subscription): --rg kockpit-dev --subscription "DJ - Referentiel Client Unifie - Dev",
# with the tunnel opened on the dev cluster.
#
# While the node is down its shards are unavailable (audit indices have 0 replicas): writes to
# indices whose shards live on it fail for those minutes. Pause the audit stream, or run it at a
# quiet hour. maintenance-migrate.sh does all of it: stops the writers, runs this script node after
# node, restarts the writers.
set -euo pipefail

URL=http://localhost:9200
DATA_PATH=/data/opensearch
SIZE=""
SUB=()
while [ $# -gt 0 ]; do
  case "$1" in
    --rg) RG=$2; shift 2 ;;
    --vm) VM=$2; shift 2 ;;
    --heap) HEAP=$2; shift 2 ;;
    --size) SIZE=$2; shift 2 ;;
    --subscription) SUB=(--subscription "$2"); shift 2 ;;
    --url) URL=$2; shift 2 ;;
    --data-path) DATA_PATH=$2; shift 2 ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
: "${RG:?--rg}" "${VM:?--vm}" "${HEAP:?--heap}"
HERE=$(cd "$(dirname "$0")" && pwd)

log() { echo "$(date -u +%H:%M:%S) $*"; }
health() { curl -s -m 10 "$URL/_cluster/health" | python3 -c 'import json,sys; h=json.load(sys.stdin); print(h["status"], h["number_of_nodes"], h["unassigned_shards"])'; }
allocation() { curl -s -m 10 -XPUT "$URL/_cluster/settings" -H 'Content-Type: application/json' \
  -d "{\"persistent\":{\"cluster.routing.allocation.enable\":$1}}" > /dev/null; }
node_in_cluster() { curl -s -m 10 "$URL/_cat/nodes?h=name" | grep -qx "$VM"; }
run_on_vm() {
  az vm run-command invoke ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --command-id RunShellScript "$@" \
    --query 'value[0].message' -o tsv
}

# The tunnel must point at the cluster that holds this VM.
node_in_cluster || { echo "$VM is not a node of the cluster behind $URL (wrong tunnel?)"; exit 1; }

read -r STATUS NODES UNASSIGNED <<< "$(health)"
log "cluster: $STATUS, $NODES nodes, $UNASSIGNED unassigned"
[ "$STATUS" = green ] || { echo "cluster is not green: fix that first"; exit 1; }

CURRENT_SIZE=$(az vm show ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --query hardwareProfile.vmSize -o tsv)
RESIZE=no
[ -n "$SIZE" ] && [ "$SIZE" != "$CURRENT_SIZE" ] && RESIZE=yes
log "$VM: size $CURRENT_SIZE${SIZE:+ -> $SIZE} (resize: $RESIZE), heap -> $HEAP, data -> $DATA_PATH"

# Keep the cluster from shuffling shards around while the node is away.
allocation '"primaries"'
curl -s -m 120 -XPOST "$URL/_flush" > /dev/null || true
trap 'log "restoring shard allocation"; allocation null' EXIT

ARGS=("$HEAP" "$DATA_PATH")
[ "$RESIZE" = yes ] && ARGS+=("--no-start")
log "running migrate-node.sh on $VM"
OUT=$(run_on_vm --scripts @"$HERE/migrate-node.sh" --parameters "${ARGS[@]}")
echo "$OUT" | sed 's/^/    /'
if ! grep -q MIGRATE_OK <<< "$OUT"; then
  echo "migrate-node.sh did not succeed on $VM: OpenSearch may be stopped there, see the output above"
  exit 1
fi

if [ "$RESIZE" = yes ]; then
  log "resizing $VM to $SIZE (the VM reboots; OpenSearch starts on boot)"
  if ! az vm resize ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --size "$SIZE" -o none; then
    echo "resize failed: starting OpenSearch again on the current size"
    run_on_vm --scripts "systemctl start opensearch.service" > /dev/null
    exit 1
  fi
fi

log "waiting for $VM to rejoin"
for i in $(seq 1 60); do node_in_cluster && break; sleep 10; done
node_in_cluster || { echo "$VM did not rejoin within 10 min: check 'journalctl -u opensearch' on it"; exit 1; }

allocation null
trap - EXIT
log "waiting for green"
for i in $(seq 1 90); do
  read -r STATUS NODES UNASSIGNED <<< "$(health)"
  [ "$STATUS" = green ] && break
  sleep 10
done
log "cluster: $STATUS, $NODES nodes, $UNASSIGNED unassigned"
[ "$STATUS" = green ] || { echo "not green after 15 min"; exit 1; }
curl -s "$URL/_cat/nodes?v&h=name,heap.max,ram.max,disk.used_percent" | grep -E "^name|^$VM"
log "$VM done"
