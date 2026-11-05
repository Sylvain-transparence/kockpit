#!/bin/bash
# Migrates SEVERAL OpenSearch nodes in one maintenance window, with the writers stopped:
#   1. pre-flight: tunnel on the right cluster, green, no reindex running, writer images
#   2. stops the writer container apps (deactivates their active revision): audits wait in Event Hubs
#   3. runs rolling-migrate.sh node after node, asking before each one (unless --yes)
#   4. reactivates the writers and waits until the newest audit is fresh again (--check-index)
# Each step asks for confirmation. If anything fails, the writers stay stopped (nothing is lost while
# Event Hubs retains the events) and the commands to restart them are printed.
#
#   maintenance-migrate.sh --rg kockpit-pro --heap 4g \
#       --node opensearch-2-pro:Standard_B2ms --node opensearch-3-pro:Standard_B2ms ... \
#       --app kockpit-audit-stream-pro --app kockpit-audit-stream-rescue-pro \
#       --check-index rcu-audit-data-pro-read \
#       [--subscription <id or name>] [--url http://localhost:9200] [--data-path /data/opensearch]
#       [--max-lag 5] [--yes] [--allow-tasks] [--allow-untagged-images]
#
# --node NAME[:SIZE]: SIZE resizes the VM (reboot), leave it out when the VM already has the right size.
# Writers must run an image with a date tag (YYYYMMDD-HHMMSS): those drain their in-memory queue on
# shutdown; older ones (e.g. dev-6271c37, 1.1.1) can lose it. --allow-untagged-images skips that check.
set -euo pipefail

URL=http://localhost:9200
DATA_PATH=/data/opensearch
MAX_LAG=5
YES=no
ALLOW_TASKS=no
ALLOW_UNTAGGED=no
CHECK_INDEX=""
SUB=()
SUB_TEXT=""
NODES=()
APPS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --rg) RG=$2; shift 2 ;;
    --heap) HEAP=$2; shift 2 ;;
    --node) NODES+=("$2"); shift 2 ;;
    --app) APPS+=("$2"); shift 2 ;;
    --check-index) CHECK_INDEX=$2; shift 2 ;;
    --subscription) SUB=(--subscription "$2"); SUB_TEXT=" --subscription \"$2\""; shift 2 ;;
    --url) URL=$2; shift 2 ;;
    --data-path) DATA_PATH=$2; shift 2 ;;
    --max-lag) MAX_LAG=$2; shift 2 ;;
    --yes) YES=yes; shift ;;
    --allow-tasks) ALLOW_TASKS=yes; shift ;;
    --allow-untagged-images) ALLOW_UNTAGGED=yes; shift ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
: "${RG:?--rg}" "${HEAP:?--heap}"
[ ${#NODES[@]} -gt 0 ] || { echo "at least one --node"; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
LOG="maintenance-migrate-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee -a "$LOG") 2>&1

log() { echo "$(date -u +%H:%M:%S) $*"; }
fail() { echo "[FAILED] $*"; exit 1; }
confirm() {
  [ "$YES" = yes ] && return 0
  local answer
  read -r -p "$1 [y/N] " answer < /dev/tty
  [ "$answer" = y ] || [ "$answer" = Y ]
}
AZ() { az "$@" ${SUB[@]+"${SUB[@]}"}; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); $1"; }

cluster_nodes() { curl -s -m 10 "$URL/_cat/nodes?h=name"; }
health() { curl -s -m 10 "$URL/_cluster/health" | json 'print(d["status"], d["number_of_nodes"], d["unassigned_shards"])'; }
reindex_tasks() { curl -s -m 10 "$URL/_tasks?actions=*reindex" | json 'print(sum(len(n["tasks"]) for n in d["nodes"].values()))'; }
newest_lag_min() {
  # minutes between now and the newest "start" in the index (empty if none)
  curl -s -m 30 "$URL/$CHECK_INDEX/_search" -H 'Content-Type: application/json' \
    -d '{"size":0,"aggs":{"last":{"max":{"field":"start"}}}}' |
    json 'import time; v=d["aggregations"]["last"]["value"]; print("" if v is None else int((time.time()*1000-v)/60000))'
}
active_revision() { AZ containerapp revision list -g "$RG" -n "$1" --query "[?properties.active].name | [0]" -o tsv; }
replicas() { AZ containerapp replica list -g "$RG" -n "$1" --revision "$2" --query 'length(@)' -o tsv 2>/dev/null || echo 0; }

# ---------------------------------------------------------------- 1. pre-flight
log "pre-flight checks on $URL"
IN_CLUSTER=$(cluster_nodes) || fail "cannot reach $URL: is the tunnel open?"
for spec in "${NODES[@]}"; do
  vm=${spec%%:*}
  grep -qx "$vm" <<< "$IN_CLUSTER" || fail "$vm is not a node of the cluster behind $URL (wrong tunnel?)"
done
read -r STATUS COUNT UNASSIGNED <<< "$(health)"
log "cluster: $STATUS, $COUNT nodes, $UNASSIGNED unassigned"
[ "$STATUS" = green ] || fail "cluster is not green: fix that first"
TASKS=$(reindex_tasks)
if [ "$TASKS" != 0 ]; then
  [ "$ALLOW_TASKS" = yes ] || fail "$TASKS reindex task(s) running: a node restart would break them (--allow-tasks to go on anyway)"
  log "[WARNING] $TASKS reindex task(s) running, going on (--allow-tasks)"
fi

REVS=()
for app in "${APPS[@]+"${APPS[@]}"}"; do
  image=$(AZ containerapp show -g "$RG" -n "$app" --query 'properties.template.containers[0].image' -o tsv) \
    || fail "container app $app not found in $RG"
  rev=$(active_revision "$app")
  log "writer $app: image ${image##*/}, active revision ${rev:-none}"
  if ! [[ "${image##*:}" =~ ^[0-9]{8}-[0-9]{6}$ ]]; then
    [ "$ALLOW_UNTAGGED" = yes ] || fail "$app runs ${image##*:}: older images can lose their in-memory queue when stopped. Deploy a date-tagged image first (--allow-untagged-images to skip)"
    log "[WARNING] $app runs ${image##*:} (--allow-untagged-images)"
  fi
  REVS+=("$rev")
done
[ ${#APPS[@]} -gt 0 ] || log "[WARNING] no --app given: writers keep running, writes to a stopped node fail"
if [ -n "$CHECK_INDEX" ]; then
  log "newest audit in $CHECK_INDEX: $(newest_lag_min) min old"
fi

echo
echo "Plan (rg $RG, heap $HEAP, data $DATA_PATH, log $LOG):"
for app in "${APPS[@]+"${APPS[@]}"}"; do echo "  - stop $app"; done
for spec in "${NODES[@]}"; do
  vm=${spec%%:*}; size=""; [ "$spec" != "$vm" ] && size=${spec#*:}
  echo "  - migrate $vm${size:+, resize to $size}"
done
for app in "${APPS[@]+"${APPS[@]}"}"; do echo "  - restart $app"; done
echo
confirm "Go?" || fail "aborted, nothing changed"

# ---------------------------------------------------------------- 2. stop the writers
STOPPED=()
print_restart() {
  [ ${#STOPPED[@]} -gt 0 ] || return 0
  echo
  echo "[ACTION NEEDED] writers are still STOPPED. Once the cluster is green, restart them with:"
  for entry in "${STOPPED[@]}"; do
    echo "  az containerapp revision activate -g $RG -n ${entry%%=*} --revision ${entry#*=}$SUB_TEXT"
  done
}
trap print_restart EXIT

i=0
for app in "${APPS[@]+"${APPS[@]}"}"; do
  rev=${REVS[$i]}; i=$((i + 1))
  [ -n "$rev" ] || { log "$app has no active revision, nothing to stop"; continue; }
  log "stopping $app (deactivating $rev)"
  AZ containerapp revision deactivate -g "$RG" -n "$app" --revision "$rev" -o none
  STOPPED+=("$app=$rev")
done
for entry in "${STOPPED[@]+"${STOPPED[@]}"}"; do
  app=${entry%%=*}; rev=${entry#*=}
  for _ in $(seq 1 30); do [ "$(replicas "$app" "$rev")" = 0 ] && break; sleep 10; done
  [ "$(replicas "$app" "$rev")" = 0 ] || fail "$app still has replicas after 5 min"
  log "$app: 0 replicas"
done

# ---------------------------------------------------------------- 3. migrate, node after node
n=0
for spec in "${NODES[@]}"; do
  n=$((n + 1))
  vm=${spec%%:*}; size=""; [ "$spec" != "$vm" ] && size=${spec#*:}
  echo
  confirm "[$n/${#NODES[@]}] migrate $vm${size:+ and resize it to $size}?" || fail "stopped before $vm"
  ARGS=(--rg "$RG" --vm "$vm" --heap "$HEAP" --url "$URL" --data-path "$DATA_PATH")
  [ -n "$size" ] && ARGS+=(--size "$size")
  [ ${#SUB[@]} -gt 0 ] && ARGS+=("${SUB[@]}")
  "$HERE/rolling-migrate.sh" "${ARGS[@]}" || fail "$vm: see rolling-migrate.sh output above"
  log "[OK] [$n/${#NODES[@]}] $vm migrated"
done

# ---------------------------------------------------------------- 4. restart the writers
echo
read -r STATUS COUNT UNASSIGNED <<< "$(health)"
[ "$STATUS" = green ] || fail "cluster is $STATUS after the migration: not restarting the writers"
confirm "All nodes migrated, cluster green. Restart the writers?" || fail "writers left stopped on request"
for entry in "${STOPPED[@]+"${STOPPED[@]}"}"; do
  app=${entry%%=*}; rev=${entry#*=}
  log "restarting $app (activating $rev)"
  AZ containerapp revision activate -g "$RG" -n "$app" --revision "$rev" -o none
done
STOPPED_COPY=("${STOPPED[@]+"${STOPPED[@]}"}")
STOPPED=()
for entry in "${STOPPED_COPY[@]+"${STOPPED_COPY[@]}"}"; do
  app=${entry%%=*}; rev=${entry#*=}
  for _ in $(seq 1 30); do [ "$(replicas "$app" "$rev")" != 0 ] && break; sleep 10; done
  log "$app: $(replicas "$app" "$rev") replica(s)"
done

if [ -n "$CHECK_INDEX" ]; then
  log "waiting for the stream to catch up (newest audit in $CHECK_INDEX under $MAX_LAG min, up to 60 min)"
  for _ in $(seq 1 120); do
    lag=$(newest_lag_min || true)
    log "  newest audit: ${lag:-?} min old"
    [ -n "$lag" ] && [ "$lag" -lt "$MAX_LAG" ] && break
    sleep 30
  done
  [ -n "$lag" ] && [ "$lag" -lt "$MAX_LAG" ] || fail "still behind after 60 min: check the stream logs"
fi

echo
log "[OK] maintenance done. Before the next terraform apply on this environment, set the VM sizes in"
log "     kockpit-tf (vm_sizes_per_env) to the sizes used here, or Terraform resizes the VMs back."
curl -s "$URL/_cat/nodes?v&h=name,heap.max,ram.max,disk.used_percent&s=name"
