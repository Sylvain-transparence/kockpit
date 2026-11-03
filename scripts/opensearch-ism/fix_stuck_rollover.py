#!/usr/bin/env python3
"""
Unstick ISM-managed indices whose rollover failed with "Missing alias or not the write index".

Why they're stuck: the audit stream moves each write alias to a new daily index itself, so when
ISM later tries the policy's rollover on yesterday's index, that index no longer holds the alias.
The rollover fails, ISM disables the job, and the index stays in "hot" forever - it never reaches
the policy's delete transition, so it is never deleted.

These indices don't need a rollover any more (they receive no writes), only their retention. For
each one, this script:
  1. reads the retention from its current policy's delete transition (min_index_age),
  2. moves it to a delete-only policy with that same retention ("audit_delete_only_ttl<N>d",
     created if missing, without ism_template so it never auto-attaches to anything),
     via _plugins/_ism/remove + _plugins/_ism/add.

min_index_age counts from the index's creation date, so an index already older than its retention
is deleted at the next ISM run - the plan lists those separately. Indices holding a write alias
(explicitly, or as an alias's only index) are never touched.

Python 3.9+ standard library only. Default is a dry run; --apply makes the changes.

  ./fix_stuck_rollover.py --host https://localhost:9200 --insecure                # plan
  ./fix_stuck_rollover.py --host https://localhost:9200 --insecure --apply        # asks to confirm
  ./fix_stuck_rollover.py --host https://localhost:9200 --insecure --index-pattern 'offers-*'
"""
import argparse
import collections
import fnmatch
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

STUCK_MESSAGE = "Missing alias or not the write index"


class OpenSearch:
    def __init__(self, host, insecure):
        self.host = host.rstrip("/")
        self.ctx = ssl._create_unverified_context() if insecure else None

    def request(self, method, path, body=None, ok_404=False):
        req = urllib.request.Request(self.host + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=300, context=self.ctx) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            if ok_404 and e.code == 404:
                return None
            sys.exit(f"❌ {method} {path} -> HTTP {e.code}: {e.read().decode(errors='replace')[:1000]}")
        except urllib.error.URLError as e:
            sys.exit(f"❌ {method} {path} -> {e.reason} (is the tunnel open?)")


def write_indices(aliases):
    members = collections.defaultdict(list)
    for index, a in aliases.items():
        for alias, conf in a.get("aliases", {}).items():
            members[alias].append((index, conf.get("is_write_index")))
    targets = set()
    for alias, m in members.items():
        targets |= {i for i, w in m if w is True}
        if len(m) == 1 and m[0][1] is not False:
            targets.add(m[0][0])
    return targets


def received_recently(os_, index, window="1h"):
    """True if the index holds documents indexed within `window` (@timestamp = indexing time)."""
    body = {"query": {"range": {"@timestamp": {"gte": f"now-{window}"}}}}
    result = os_.request("POST", f"/{index}/_count", body, ok_404=True)
    return bool(result and result.get("count"))


def delete_ttl(policy):
    """min_index_age of the transition leading to the state that holds the delete action."""
    states = policy.get("states", [])
    deleting = {s["name"] for s in states if any("delete" in a for a in s.get("actions", []))}
    for state in states:
        for t in state.get("transitions", []):
            if t.get("state_name") in deleting:
                age = (t.get("conditions") or {}).get("min_index_age")
                if age:
                    return age
    return None


def delete_only_policy(ttl):
    return {"policy": {
        "description": f"Delete {ttl} after index creation, no rollover. For indices whose rollover "
                       f"can't run because the audit stream manages their write alias.",
        "default_state": "hot",
        "states": [
            {"name": "hot", "actions": [],
             "transitions": [{"state_name": "delete", "conditions": {"min_index_age": ttl}}]},
            {"name": "delete", "actions": [{"delete": {}}], "transitions": []},
        ],
    }}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="https://localhost:9200")
    p.add_argument("--insecure", action="store_true", help="Skip TLS verification (tunnel)")
    p.add_argument("--index-pattern", default="*", help="Only consider indices matching this glob")
    p.add_argument("--policy-prefix", default="audit_delete_only_ttl")
    p.add_argument("--apply", action="store_true", help="Make the changes (default: dry run)")
    p.add_argument("--yes", action="store_true", help="With --apply, don't ask for confirmation")
    args = p.parse_args()
    os_ = OpenSearch(args.host, args.insecure)

    explain = {}
    start = 0
    while True:
        page = os_.request("GET", f"/_plugins/_ism/explain?size=1000&from={start}")
        entries = {k: v for k, v in page.items() if isinstance(v, dict) and "index" in v}
        explain.update(entries)
        start += 1000
        if not entries or start >= page.get("total_managed_indices", 0):
            break
    aliases = os_.request("GET", "/_alias")
    cat = {r["index"]: r for r in os_.request(
        "GET", "/_cat/indices?format=json&h=index,creation.date,pri.store.size&bytes=b")}
    policies = {p_["_id"]: p_["policy"] for p_ in os_.request("GET", "/_plugins/_ism/policies?size=1000")["policies"]}
    writers = write_indices(aliases)

    now_ms = time.time() * 1000
    plan, skipped = [], []
    for index, e in sorted(explain.items()):
        if not fnmatch.fnmatch(index, args.index_pattern):
            continue
        message = (e.get("info") or {}).get("message", "")
        if (e.get("action") or {}).get("name") != "rollover" or STUCK_MESSAGE not in message:
            continue
        if index in writers:
            skipped.append((index, "holds a write alias"))
            continue
        # A concrete index squatting a write-alias name (auto-created by a bulk write while the
        # alias was missing) receives every write for its prefix: its creation date says nothing
        # about its data's age, so a delete-only policy would wipe recent data with it.
        if index.endswith("-write") or index.endswith("-read"):
            skipped.append((index, "concrete index named like an alias - fix the alias first"))
            continue
        if received_recently(os_, index):
            skipped.append((index, "received documents in the last hour - still being written to"))
            continue
        policy_id = e.get("policy_id")
        ttl = delete_ttl(policies.get(policy_id, {}))
        if not ttl or not re.fullmatch(r"\d+d", ttl):
            skipped.append((index, f"no usable delete min_index_age in policy {policy_id} ({ttl})"))
            continue
        created = int(cat[index]["creation.date"]) if index in cat else None
        if created is None:
            skipped.append((index, "not found in _cat/indices"))
            continue
        delete_at = created + int(ttl[:-1]) * 86400000
        plan.append({"index": index, "policy": policy_id, "ttl": ttl,
                     "size": int(cat[index].get("pri.store.size") or 0),
                     "delete_at": delete_at, "overdue": delete_at <= now_ms})

    for index, reason in skipped:
        print(f"⚠️  skip {index}: {reason}")
    if not plan:
        print("No stuck index to fix.")
        return

    fmt = lambda ms: datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    print(f"\n{'index':<58} {'ttl':>4} {'size':>9}  deleted")
    for item in sorted(plan, key=lambda x: (not x["overdue"], x["delete_at"])):
        when = "AT NEXT ISM RUN (past retention)" if item["overdue"] else fmt(item["delete_at"])
        print(f"{item['index']:<58} {item['ttl']:>4} {item['size'] / 1e9:>7.1f}GB  {when}")
    overdue = [x for x in plan if x["overdue"]]
    print(f"\n{len(plan)} indices to move to delete-only policies "
          f"({sum(x['size'] for x in plan) / 1e12:.2f} TB); "
          f"{len(overdue)} of them already past retention ({sum(x['size'] for x in overdue) / 1e12:.2f} TB) "
          f"will be deleted at the next ISM run.")
    ttls = sorted({x["ttl"] for x in plan}, key=lambda t: int(t[:-1]))
    print("Delete-only policies used: " + ", ".join(f"{args.policy_prefix}{t}" for t in ttls))

    if not args.apply:
        print("\nDry run - nothing changed. Re-run with --apply to make these changes.")
        return
    if not args.yes:
        try:
            answer = input(f"\nApply to {len(plan)} indices on {args.host}? Type APPLY to confirm: ")
        except EOFError:
            sys.exit("No terminal to confirm from - nothing changed (use --yes).")
        if answer.strip() != "APPLY":
            sys.exit("Aborted - nothing changed.")

    for ttl in ttls:
        policy_id = f"{args.policy_prefix}{ttl}"
        if os_.request("GET", f"/_plugins/_ism/policies/{policy_id}", ok_404=True) is None:
            os_.request("PUT", f"/_plugins/_ism/policies/{policy_id}", delete_only_policy(ttl))
            print(f"✅ created policy {policy_id}")
        else:
            print(f"   policy {policy_id} already exists")

    failures = 0
    for item in plan:
        index, policy_id = item["index"], f"{args.policy_prefix}{item['ttl']}"
        removed = os_.request("POST", f"/_plugins/_ism/remove/{index}")
        added = os_.request("POST", f"/_plugins/_ism/add/{index}", {"policy_id": policy_id})
        if removed.get("failures") or added.get("failures"):
            failures += 1
            print(f"❌ {index}: remove={removed.get('failed_indices')} add={added.get('failed_indices')}")
        else:
            print(f"   {index} -> {policy_id}")
    print(f"\n{'✅' if not failures else '⚠️ '} {len(plan) - failures}/{len(plan)} indices moved. "
          f"ISM picks them up at its next job run (every few minutes).")


if __name__ == "__main__":
    main()
