#!/usr/bin/env bash
# Step 3 - snapshot the listed indices into the repository registered in step 2, then follow it.
# Runs entirely on the cluster (data nodes -> S3); this script only starts and watches it, so it
# can be interrupted and re-run with --status-only without affecting the snapshot.
#
#   ./03_snapshot.sh                 # start + follow
#   ./03_snapshot.sh --status-only   # just follow an already-started snapshot
#
# Env: HOST (default https://localhost:9200 through the tunnel), REPO, SNAPSHOT, INDICES_FILE,
#      DOMAIN, CUTOFF_MS (only recorded in the snapshot metadata). 00_plan.py prints them all.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${HOST:-https://localhost:9200}"
REPO="${REPO:-orders-archive}"
SNAPSHOT="${SNAPSHOT:-orders-before-20260930-1215}"
INDICES_FILE="${INDICES_FILE:-orders_20260930-1215_snapshot.txt}"
CURL=(curl -sk --fail-with-body -m 60 -H 'Content-Type: application/json')

if [[ "${1:-}" != "--status-only" ]]; then
  indices=$(grep -v '^\s*$' "$INDICES_FILE" | paste -sd, -)
  count=$(grep -cv '^\s*$' "$INDICES_FILE")
  echo "➡️  Snapshot ${REPO}/${SNAPSHOT}: ${count} indices from ${INDICES_FILE}"
  # partial=false: fail rather than silently archive an index with missing shards.
  # include_global_state=false: only the indices - cluster state/system indices don't belong
  # in this archive (and can't be restored on a managed domain anyway).
  "${CURL[@]}" -X PUT "${HOST}/_snapshot/${REPO}/${SNAPSHOT}?wait_for_completion=false" -d "{
    \"indices\": \"${indices}\",
    \"ignore_unavailable\": false,
    \"include_global_state\": false,
    \"partial\": false,
    \"metadata\": { \"reason\": \"audit data archived before deletion\", \"domain\": \"${DOMAIN:-orders}\", \"before_epoch_ms\": ${CUTOFF_MS:-1790763300000}, \"indices_file\": \"$(basename "$INDICES_FILE")\" }
  }" || { echo; echo "❌ Could not start the snapshot. If it says another snapshot is running (the hourly automated one), wait a few minutes and re-run."; exit 1; }
  echo
fi

echo "➡️  Following ${REPO}/${SNAPSHOT} (Ctrl+C stops watching, not the snapshot)"
while true; do
  status_json=$("${CURL[@]}" "${HOST}/_snapshot/${REPO}/${SNAPSHOT}/_status") \
    || { echo "❌ Cannot read status: ${status_json:-no response}"; exit 1; }
  line=$(STATUS="$status_json" python3 <<'EOF'
import json, os
s = json.loads(os.environ["STATUS"])["snapshots"][0]
shards = s["shards_stats"]
total_b = s["stats"].get("total", {}).get("size_in_bytes", 0)
done_b = s["stats"].get("processed", {}).get("size_in_bytes", 0)
# A finished snapshot no longer reports per-file progress ("processed" reads 0).
if s["state"] == "SUCCESS":
    done_b = total_b
pct = 100 * done_b / total_b if total_b else 0
print(f"{s['state']}\t{shards['done']}/{shards['total']} shards done, {shards['failed']} failed\t"
      f"{done_b / 1e9:.1f}/{total_b / 1e9:.1f} GB ({pct:.0f}%)")
EOF
)
  state=${line%%$'\t'*}
  echo "$(date '+%H:%M:%S')  ${line//$'\t'/  }"
  case "$state" in
    SUCCESS) break ;;
    FAILED|PARTIAL|ABORTED) echo "❌ Snapshot ended in state $state"; exit 1 ;;
  esac
  sleep "${POLL_SECONDS:-60}"
done

# The snapshot info (not _status) is the authoritative final record.
info_json=$("${CURL[@]}" "${HOST}/_snapshot/${REPO}/${SNAPSHOT}")
INFO="$info_json" python3 <<'EOF'
import json, os
s = json.loads(os.environ["INFO"])["snapshots"][0]
sh = s["shards"]
print(f"\n✅ {s['snapshot']}: state={s['state']}, indices={len(s['indices'])}, "
      f"shards ok={sh['successful']}/{sh['total']}, failed={sh['failed']}, {s['start_time']} -> {s['end_time']}")
EOF
