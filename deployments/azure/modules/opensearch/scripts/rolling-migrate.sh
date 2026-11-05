#!/bin/bash
# Migrates ONE existing OpenSearch node (mount point, heap, systemd unit, optional VM resize), from a
# workstation with `az` logged in and the cluster reachable (tunnel). Run it node after node, and
# only start the next one once this one reports the cluster green.
#
#   rolling-migrate.sh --rg kockpit-pro --vm opensearch-2-pro --heap 4g [--size Standard_B2ms]
#                      [--disk-type Premium_LRS] [--subscription <id or name>] [--url http://localhost:9200]
#                      [--data-path /data/opensearch]
#
# --disk-type changes the storage type of the data disk (LUN 11): the VM is deallocated (and resized in the
# same stop if --size is given), the disk updated, the VM started. Its data stays. Safe only because
# migrate-node.sh has moved the mount off /mnt (wiped on deallocation) just before.
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
DISK_TYPE=""
SUB=()
while [ $# -gt 0 ]; do
  case "$1" in
    --rg) RG=$2; shift 2 ;;
    --vm) VM=$2; shift 2 ;;
    --heap) HEAP=$2; shift 2 ;;
    --size) SIZE=$2; shift 2 ;;
    --disk-type) DISK_TYPE=$2; shift 2 ;;
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

read -r CURRENT_SIZE LOCATION DISK_ID <<< "$(az vm show ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" \
  --query '[hardwareProfile.vmSize, location, storageProfile.dataDisks[?lun==`11`].managedDisk.id | [0]]' -o tsv | tr '\n' ' ')"
[ -n "$CURRENT_SIZE" ] || { echo "cannot read VM $VM in $RG"; exit 1; }
RESIZE=no
[ -n "$SIZE" ] && [ "$SIZE" != "$CURRENT_SIZE" ] && RESIZE=yes
TARGET_SIZE=${SIZE:-$CURRENT_SIZE}
RETYPE=no
if [ -n "$DISK_TYPE" ]; then
  [ -n "$DISK_ID" ] && [ "$DISK_ID" != None ] || { echo "$VM has no data disk on LUN 11"; exit 1; }
  CURRENT_DISK_TYPE=$(az disk show ${SUB[@]+"${SUB[@]}"} --ids "$DISK_ID" --query sku.name -o tsv)
  [ "$DISK_TYPE" != "$CURRENT_DISK_TYPE" ] && RETYPE=yes
  if [ "$RETYPE" = yes ] && [ "$DISK_TYPE" = Premium_LRS ]; then
    PREMIUM=$(az vm list-skus ${SUB[@]+"${SUB[@]}"} -l "$LOCATION" --size "$TARGET_SIZE" --resource-type virtualMachines \
      --query "[?name=='$TARGET_SIZE'] | [0].capabilities[?name=='PremiumIO'].value | [0]" -o tsv)
    [ "$PREMIUM" = True ] || { echo "$TARGET_SIZE does not support Premium disks (PremiumIO: ${PREMIUM:-unknown})"; exit 1; }
  fi
fi
PRIVATE_IP=$(az vm show -d ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --query privateIps -o tsv)
log "$VM ($PRIVATE_IP): size $CURRENT_SIZE${SIZE:+ -> $SIZE} (resize: $RESIZE), data disk ${CURRENT_DISK_TYPE:-unchanged}${DISK_TYPE:+ -> $DISK_TYPE} (change: $RETYPE), heap -> $HEAP, data -> $DATA_PATH"

# Keep the cluster from shuffling shards around while the node is away.
allocation '"primaries"'
curl -s -m 120 -XPOST "$URL/_flush" > /dev/null || true
trap 'log "restoring shard allocation"; allocation null' EXIT

ARGS=("$HEAP" "$DATA_PATH")
{ [ "$RESIZE" = yes ] || [ "$RETYPE" = yes ]; } && ARGS+=("--no-start")
log "running migrate-node.sh on $VM"
OUT=$(run_on_vm --scripts @"$HERE/migrate-node.sh" --parameters "${ARGS[@]}")
echo "$OUT" | sed 's/^/    /'
if ! grep -q MIGRATE_OK <<< "$OUT"; then
  echo "migrate-node.sh did not succeed on $VM: OpenSearch may be stopped there, see the output above"
  exit 1
fi

if [ "$RETYPE" = yes ]; then
  # Restart OpenSearch on whatever state the VM is in if a step fails.
  recover() { echo "$1: starting the VM / OpenSearch again"; az vm start ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" -o none || true
              run_on_vm --scripts "systemctl start opensearch.service" > /dev/null || true; exit 1; }
  log "deallocating $VM"
  az vm deallocate ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" -o none || recover "deallocate failed"
  log "data disk -> $DISK_TYPE"
  az disk update ${SUB[@]+"${SUB[@]}"} --ids "$DISK_ID" --sku "$DISK_TYPE" -o none || recover "disk update failed"
  if [ "$RESIZE" = yes ]; then
    log "resizing $VM to $SIZE (deallocated)"
    az vm resize ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --size "$SIZE" -o none || recover "resize failed"
  fi
  log "starting $VM (OpenSearch starts on boot)"
  az vm start ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" -o none || recover "start failed"
elif [ "$RESIZE" = yes ]; then
  log "resizing $VM to $SIZE (the VM reboots; OpenSearch starts on boot)"
  if ! az vm resize ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --size "$SIZE" -o none; then
    echo "resize failed: starting OpenSearch again on the current size"
    run_on_vm --scripts "systemctl start opensearch.service" > /dev/null
    exit 1
  fi
fi

NEW_IP=$(az vm show -d ${SUB[@]+"${SUB[@]}"} -g "$RG" -n "$VM" --query privateIps -o tsv)
if [ "$NEW_IP" != "$PRIVATE_IP" ]; then
  echo "[WARNING] $VM private IP changed $PRIVATE_IP -> $NEW_IP: the private DNS record opensearch-node-* used by the"
  echo "          apps still points at the old one. Update it (az network private-dns record-set a ...) or run terraform apply."
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
