#!/bin/bash
# Read-only health check of the ISM (Index State Management) lifecycle of the audit indices.
# Run it from a workstation with the cluster reachable (tunnel). It never writes to the cluster.
#
#   ism-check.sh [--url http://localhost:9200] [--pattern 'rcu-audit-data-pro-*'] [--list]
#
# Reports, for the indices matching --pattern:
#   1. number of indices per family (name without the -YYYY.MM.DD-NNNNN suffix)
#   2. indices past their retention (the ttlNd in the name, age from creation date) still present
#   3. failed ISM actions (e.g. rollover "Missing alias or not the write index")
#   4. indices on an OLD version of their policy (policy_seq_no != the policy's current _seq_no):
#      fix = POST _plugins/_ism/remove/<idx> then POST _plugins/_ism/add/<idx> {"policy_id": ...}
#   5. indices with no policy at all (recovery-* clones are unmanaged on purpose)
#   6. disk per node
# --list prints the index names of sections 2-5 (otherwise the first 5 of each).
# Exit code 1 if any failed action or old policy version is found, 0 otherwise.
#
# Generic OpenSearch (REST + ISM plugin only, curl and python3), not tied to Azure.
#
# Examples: prod  ism-check.sh --pattern 'rcu-audit-data-pro-*'
#           dev   ism-check.sh --pattern 'rcu-audit-data-oat-*'   (dev cluster tunnel)
#                 ism-check.sh --pattern '*-ttl*'                 (everything with a TTL)
set -euo pipefail

URL=http://localhost:9200
PATTERN='rcu-audit-data-pro-*'
LIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL=$2; shift 2 ;;
    --pattern) PATTERN=$2; shift 2 ;;
    --list) LIST=1; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

curl -sf "$URL" >/dev/null || { echo "cluster not reachable at $URL (tunnel open?)" >&2; exit 2; }

echo "== $(date -u +'%Y-%m-%d %H:%M:%S UTC')  cluster: $(curl -s "$URL/_cat/nodes?h=name" | sort | tr '\n' ' ')"
echo "== pattern: $PATTERN"

URL="$URL" PATTERN="$PATTERN" LIST="$LIST" python3 - <<'EOF'
import collections, json, os, re, sys, time, urllib.parse, urllib.request

U, PAT, LIST = os.environ["URL"], os.environ["PATTERN"], os.environ["LIST"] == "1"
get = lambda p: json.load(urllib.request.urlopen(U + p, timeout=120))
q = urllib.parse.quote(PAT, safe="*,")

indices = get(f"/_cat/indices/{q}?format=json&h=index,creation.date,docs.count,store.size&bytes=b&expand_wildcards=open")
explain = get(f"/_plugins/_ism/explain/{q}?size=5000")
policies = {p["_id"]: p["_seq_no"] for p in get("/_plugins/_ism/policies?size=1000")["policies"]}
now = time.time() * 1000

def family(name):
    return re.sub(r"-\d{4}\.\d{2}\.\d{2}-\d+$", "", name)

def show(title, rows):
    print(f"\n-- {title}: {len(rows)}")
    for r in (rows if LIST else rows[:5]):
        print("   ", r)
    if not LIST and len(rows) > 5:
        print(f"    ... ({len(rows) - 5} more, --list to see all)")

print("\n-- indices per family")
fam = collections.Counter(family(i["index"]) for i in indices)
size = collections.Counter()
for i in indices:
    size[family(i["index"])] += int(i["store.size"] or 0)
for f in sorted(fam):
    print(f"   {fam[f]:5d}  {size[f] / 1e9:8.1f} GB  {f}")

overdue = []
for i in indices:
    m = re.search(r"-ttl(\d+)d-", i["index"])
    if m and i.get("creation.date"):
        age = (now - int(i["creation.date"])) / 86400000
        if age > int(m.group(1)):
            overdue.append(f"{i['index']}  age {age:.0f}d > ttl {m.group(1)}d")
show("past retention, still present (ISM deletes them within a few 5-min passes; stuck if it stays)", sorted(overdue))

failed, old, unmanaged, states = [], [], [], collections.Counter()
for name, v in sorted(explain.items()):
    if not isinstance(v, dict):
        continue
    pid = v.get("index.plugins.index_state_management.policy_id") or v.get("policy_id")
    if not pid:
        unmanaged.append(name)
        continue
    action = v.get("action") or {}
    states[(pid, (v.get("state") or {}).get("name"), action.get("name"))] += 1
    if action.get("failed"):
        failed.append(f"{name}  {action.get('name')}: {(v.get('info') or {}).get('message')}")
    seq = v.get("policy_seq_no")
    if seq is not None and pid in policies and seq != policies[pid]:
        old.append(f"{name}  {pid} seq {seq} (current {policies[pid]})")
    elif pid not in policies:
        old.append(f"{name}  {pid} (policy does not exist anymore)")

print("\n-- managed indices per policy / state / action  (None = initializing or between two steps)")
for (pid, st, ac), n in sorted(states.items(), key=str):
    print(f"   {n:5d}  {pid}  state={st} action={ac}")
show("failed ISM actions", failed)
show("on an old policy version (remove + add to fix)", old)
show("no ISM policy", unmanaged)

print("\n-- disk per node")
print(urllib.request.urlopen(U + "/_cat/allocation?v&h=node,disk.percent,disk.used,disk.avail,shards&s=node", timeout=30).read().decode().rstrip())

sys.exit(1 if failed or old else 0)
EOF
