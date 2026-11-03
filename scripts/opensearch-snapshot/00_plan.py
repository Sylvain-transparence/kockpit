#!/usr/bin/env python3
"""
Step 0 - plan an archive-then-delete for one audit domain and cutoff. Read-only.

Counts, for every index of the domain, how many documents match
`domain == <domain> AND @timestamp < <cutoff>`, and classifies each index:

  FULL     every document matches   -> snapshot, then delete the whole index   (step 3 + 4)
  PARTIAL  some documents match     -> snapshot, then _delete_by_query on them (step 3 + 5)
  NONE     nothing matches          -> left alone

Writes <domain>_<cutoff>_snapshot.txt and <domain>_<cutoff>_delete.txt next to this script and
prints the exact commands for the next steps.

  ./00_plan.py --host https://localhost:9200 --insecure --domain offers --before "2026-10-15 00:00"
"""
import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo

PARIS = ZoneInfo("Europe/Paris")
HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="https://localhost:9200")
    p.add_argument("--insecure", action="store_true", help="Skip TLS verification (tunnel)")
    p.add_argument("--domain", required=True, help="Audit domain, e.g. orders")
    p.add_argument("--before", required=True,
                   help='Cutoff, exclusive. Paris time unless an offset is given, e.g. "2026-09-30 12:15"')
    p.add_argument("--index-pattern", help="Default: <domain>-auditdata-*")
    p.add_argument("--repository", default="orders-archive",
                   help="Snapshot repository registered in step 2 (one repository can hold every snapshot)")
    args = p.parse_args()

    ctx = ssl._create_unverified_context() if args.insecure else None

    def get(path, body=None):
        req = urllib.request.Request(args.host.rstrip("/") + path, method="POST" if body else "GET",
                                     data=json.dumps(body).encode() if body else None,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120, context=ctx) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            sys.exit(f"❌ {path} -> HTTP {e.code}: {e.read().decode(errors='replace')[:500]}")
        except urllib.error.URLError as e:
            sys.exit(f"❌ {path} -> {e.reason} (is the tunnel open?)")

    cutoff = datetime.fromisoformat(args.before)
    if cutoff.tzinfo is None:
        cutoff = cutoff.replace(tzinfo=PARIS)
    cutoff_ms = int(cutoff.timestamp() * 1000)
    label = cutoff.astimezone(PARIS).strftime("%Y%m%d-%H%M")
    pattern = args.index_pattern or f"{args.domain}-auditdata-*"
    query = {"query": {"bool": {"filter": [
        {"term": {"domain": args.domain}},
        {"range": {"@timestamp": {"lt": cutoff_ms, "format": "epoch_millis"}}},
    ]}}}

    print(f"Domain {args.domain}, indices {pattern}, before "
          f"{cutoff.astimezone(PARIS):%Y-%m-%d %H:%M %Z} ({cutoff_ms})\n")
    rows = get(f"/_cat/indices/{urllib.parse.quote(pattern, safe='*,')}?format=json&h=index,status&s=index")
    full, partial, none = [], [], []
    for r in rows:
        index = r["index"]
        if r.get("status") == "close":
            print(f"⚠️  {index} is closed - skipped, open it if it must be included")
            continue
        total = get(f"/{index}/_count")["count"]
        matching = get(f"/{index}/_count", query)["count"]
        kind = "NONE" if matching == 0 else "FULL" if matching == total else "PARTIAL"
        (none if kind == "NONE" else full if kind == "FULL" else partial).append((index, total, matching))
        print(f"  {kind:<8} {index:<60} {matching:>12} / {total}")

    if not full and not partial:
        print("\nNothing matches - nothing to archive or delete.")
        return

    snapshot_file = os.path.join(HERE, f"{args.domain}_{label}_snapshot.txt")
    delete_file = os.path.join(HERE, f"{args.domain}_{label}_delete.txt")
    with open(snapshot_file, "w") as f:
        f.writelines(i + "\n" for i, _, _ in full + partial)
    with open(delete_file, "w") as f:
        f.writelines(i + "\n" for i, _, _ in full)

    snapshot = f"{args.domain}-before-{label}"
    print(f"\nFULL {len(full)} indices ({sum(m for _, _, m in full)} docs), "
          f"PARTIAL {len(partial)} ({sum(m for _, _, m in partial)} matching docs), NONE {len(none)}")
    print(f"Wrote {os.path.basename(snapshot_file)} ({len(full) + len(partial)}) and "
          f"{os.path.basename(delete_file)} ({len(full)})\n")

    env = (f"HOST={args.host} REPO={args.repository} SNAPSHOT={snapshot} "
           f"DOMAIN={args.domain} CUTOFF_MS={cutoff_ms}")
    print("Next steps (from this directory):\n")
    print(f"  # 3. snapshot everything that holds matching documents")
    print(f"  {env} INDICES_FILE={os.path.basename(snapshot_file)} ./03_snapshot.sh\n")
    if full:
        print(f"  # 4. once SUCCESS: delete the fully-matching indices")
        print(f"  {env} INDICES_FILE={os.path.basename(delete_file)} ./04_delete_archived_indices.sh\n")
    if partial:
        print(f"  # 5. once SUCCESS: delete only the matching documents of the partial indices")
        idx = " ".join(f"--index {i}" for i, _, _ in partial)
        insecure = " --insecure" if args.insecure else ""
        print(f"  ../opensearch_archive_and_delete_domain.py --host {args.host}{insecure} --domain {args.domain} \\")
        print(f"    {idx} --before \"{args.before}\" --no-archive")


if __name__ == "__main__":
    main()
