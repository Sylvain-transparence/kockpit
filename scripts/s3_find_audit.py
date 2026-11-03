#!/usr/bin/env python3
"""
Find audit reports containing a given string (traceId, requestId, ...) in the S3 audit archive.

The audit stream (S3AuditConsumer) writes one object per flush and per domain/env/appId:

    s3://<bucket>/d=<domain>/e=<env>/a=<appId>/<epochMillis>-<uuid>.batch

Each object is the concatenation of the wire records as received from Kinesis, each one a
gzip member (or, for uncompressed producers, plain JSON). The key's epochMillis is the WRITE
time, so it can lag the audit's own `start` when the stream had a backlog - keep a margin.

Every object in the window is downloaded, each record decompressed, and searched for --needle
as a plain substring. Matching records are saved as JSON next to --out.

Requires boto3.

Examples:
  # Every orders app, Paris day of Sep 26 (+2h margin after)
  ./s3_find_audit.py --profile my-aws-profile --bucket kockpit-audits-pro-20260720 \\
      --domain orders --env pro --from "2026-09-26 00:00" --to "2026-09-27 02:00" \\
      --needle 24e7d823b3b8d7340e5179d7195457f4

  # Only some apps, keep going after the first hit
  ./s3_find_audit.py ... --apps orders-service,orders-basket --all
"""
import argparse
import json
import os
import queue
import sys
import threading
import time
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo

import boto3
from botocore.config import Config

PARIS = ZoneInfo("Europe/Paris")
GZIP_MAGIC = b"\x1f\x8b"


def to_millis(value):
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=PARIS)
    return int(dt.timestamp() * 1000)


def records(data):
    """Yields (offset, decompressed bytes) for each record of a .batch object."""
    i = 0
    while i < len(data):
        if data.startswith(GZIP_MAGIC, i):
            d = zlib.decompressobj(31)
            out = d.decompress(data[i:])
            end = len(data) - len(d.unused_data)
            yield i, out
            i = end
        else:
            # Plain JSON record(s): runs until the next gzip member, if any.
            nxt = data.find(GZIP_MAGIC, i + 1)
            end = len(data) if nxt < 0 else nxt
            yield i, data[i:end]
            i = end


def list_apps(s3, bucket, domain, env):
    prefix = f"d={domain}/e={env}/"
    resp = s3.list_objects_v2(Bucket=bucket, Prefix=prefix, Delimiter="/")
    return [p["Prefix"][len(prefix) + 2:].rstrip("/") for p in resp.get("CommonPrefixes", [])]


def list_keys(s3, bucket, prefix, from_ms, to_ms, out_q, stop):
    paginator = s3.get_paginator("list_objects_v2")
    count = 0
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix, StartAfter=f"{prefix}{from_ms}"):
        for obj in page.get("Contents", []):
            if stop.is_set():
                return count
            name = obj["Key"][len(prefix):]
            ts = int(name.split("-", 1)[0])
            if ts > to_ms:
                return count
            out_q.put((obj["Key"], obj["Size"]))
            count += 1
    return count


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--profile")
    p.add_argument("--bucket", required=True)
    p.add_argument("--domain", required=True)
    p.add_argument("--env", default="pro")
    p.add_argument("--apps", help="comma-separated appIds (default: all under the domain/env)")
    p.add_argument("--from", dest="from_", required=True, help="ISO datetime, Europe/Paris if no offset")
    p.add_argument("--to", required=True, help="ISO datetime, Europe/Paris if no offset")
    p.add_argument("--needle", required=True, help="string to look for, e.g. a traceId")
    p.add_argument("--workers", type=int, default=64)
    p.add_argument("--all", action="store_true", help="don't stop at the first match")
    p.add_argument("--out", default="matches", help="directory for matching records")
    args = p.parse_args()

    from_ms, to_ms = to_millis(args.from_), to_millis(args.to)
    needle = args.needle.encode()
    session = boto3.Session(profile_name=args.profile)
    s3 = session.client("s3", config=Config(max_pool_connections=args.workers + 16,
                                            retries={"max_attempts": 10, "mode": "adaptive"}))
    apps = args.apps.split(",") if args.apps else list_apps(s3, args.bucket, args.domain, args.env)
    print(f"Scanning {apps} from {from_ms} to {to_ms} for {args.needle}", file=sys.stderr)
    os.makedirs(args.out, exist_ok=True)

    keys_q = queue.Queue(maxsize=args.workers * 50)
    stop = threading.Event()
    lock = threading.Lock()
    stats = {"objects": 0, "bytes": 0, "records": 0, "errors": 0, "matches": 0, "last_key": ""}

    def scan(key, size):
        try:
            data = s3.get_object(Bucket=args.bucket, Key=key)["Body"].read()
            n = 0
            for offset, rec in records(data):
                n += 1
                if needle in rec:
                    try:
                        j = json.loads(rec)
                        summary = {k: j.get(k) for k in ("id", "requestId", "appId", "start", "end")}
                    except ValueError:
                        summary = {}
                    name = f"{key.replace('/', '_')}@{offset}.json"
                    with open(os.path.join(args.out, name), "wb") as f:
                        f.write(rec)
                    with lock:
                        stats["matches"] += 1
                    print(f"MATCH s3://{args.bucket}/{key} offset={offset} {summary} -> {name}", flush=True)
                    if not args.all:
                        stop.set()
            with lock:
                stats["objects"] += 1
                stats["bytes"] += size
                stats["records"] += n
                stats["last_key"] = key
        except Exception as e:  # keep scanning, report at the end
            with lock:
                stats["errors"] += 1
            print(f"ERROR {key}: {e}", file=sys.stderr, flush=True)

    def worker():
        while True:
            item = keys_q.get()
            if item is None:
                return
            if not stop.is_set():
                scan(*item)

    workers = [threading.Thread(target=worker, daemon=True) for _ in range(args.workers)]
    for t in workers:
        t.start()

    started = time.time()

    def progress():
        while not done.is_set():
            done.wait(15)
            with lock:
                el = time.time() - started
                print(f"[{el:6.0f}s] objects={stats['objects']} records={stats['records']} "
                      f"MB={stats['bytes'] / 1e6:.0f} ({stats['bytes'] / 1e6 / max(el, 1):.1f} MB/s) "
                      f"errors={stats['errors']} matches={stats['matches']} last={stats['last_key']}",
                      file=sys.stderr, flush=True)

    done = threading.Event()
    threading.Thread(target=progress, daemon=True).start()

    # One lister per app, interleaved into the same queue, so every app progresses in time order.
    with ThreadPoolExecutor(len(apps)) as listers:
        prefixes = [f"d={args.domain}/e={args.env}/a={a}/" for a in apps]
        totals = list(listers.map(lambda pr: list_keys(s3, args.bucket, pr, from_ms, to_ms, keys_q, stop), prefixes))
    for _ in workers:
        keys_q.put(None)
    for t in workers:
        t.join()
    done.set()

    print(f"Done in {time.time() - started:.0f}s: listed={sum(totals)} scanned={stats['objects']} "
          f"records={stats['records']} errors={stats['errors']} matches={stats['matches']}", file=sys.stderr)
    sys.exit(0 if stats["matches"] else 1)


if __name__ == "__main__":
    main()
