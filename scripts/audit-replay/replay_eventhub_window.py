#!/usr/bin/env python3
"""Replay a time window of the audit topic (Event Hubs / Kafka) into OpenSearch, throttled.

Reproduces what kockpit-audit-stream 1.1.1 indexes for each message, without the stream:
- decode the record: gzip (magic 1f 8b) or plain UTF-8 JSON, an AuditReport;
- keep the fields of the 1.1.1 AuditReport model only, in declaration order; Instants
  (start/end, read as seconds.nanoseconds) become epoch milliseconds, a missing ttl becomes
  --ttl-default;
- _id = id, or requestId when id is missing;
- target index = the existing <domain>-<suffix>-<env>-ttl<N>d-<day>-* index of the day the
  record was enqueued (UTC), NOT the write alias: replaying after midnight must still land in
  that day's index, or already-indexed audits would show up twice in the read alias.

Writes use op_type=create: an audit already in the index is left as is (counted "exists"),
so the script can be rerun safely. It reads with its own partition assignment and commits no
offsets, so it never touches the stream's consumer group.

Default is a dry run (reads only): it counts records per target index and, with
--check-existing, how many are already indexed. Add --execute to write.

Requires: pip install kafka-python
"""
import argparse
import datetime as dt
import gzip
import json
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, OrderedDict
from decimal import Decimal

from kafka import KafkaConsumer, TopicPartition


# ---------------------------------------------------------------------------------------------
# Decoding and document shape (kockpit-audit-stream 1.1.1)
# ---------------------------------------------------------------------------------------------

def decode(value: bytes):
    if len(value) >= 2 and value[0] == 0x1F and value[1] == 0x8B:
        value = gzip.decompress(value)
    return json.loads(value.decode("utf-8"), parse_float=Decimal, object_pairs_hook=OrderedDict)


def instant_to_millis(v):
    """java.time.Instant as Jackson 3 reads it (numbers = seconds[.nanos]) -> epoch millis."""
    if v is None:
        return None
    if isinstance(v, (int, Decimal)):
        seconds = Decimal(v)
        return int((seconds * 1000).to_integral_value(rounding="ROUND_FLOOR"))
    if isinstance(v, str):
        return int(dt.datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000)
    raise ValueError("unsupported instant %r" % (v,))


def date_to_millis(v):
    """java.util.Date: numbers are epoch millis already."""
    if v is None or isinstance(v, int):
        return v
    if isinstance(v, Decimal):
        return int(v)
    if isinstance(v, str):
        return int(dt.datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000)
    raise ValueError("unsupported date %r" % (v,))


def as_string(v):
    if v is None or isinstance(v, str):
        return v
    return format(v, "f") if isinstance(v, Decimal) else str(v)


def as_number(v):
    """Map<String,Object> values: Jackson reads decimals as Double, integers stay integral."""
    if isinstance(v, Decimal):
        return float(v)
    if isinstance(v, dict):
        return OrderedDict((k, as_number(x)) for k, x in v.items())
    if isinstance(v, list):
        return [as_number(x) for x in v]
    return v


def to_doc(r, ttl_default):
    kvs = r.get("indexedKeyValues")
    audits = r.get("audits")
    ttl = r.get("ttl")
    return OrderedDict([
        ("id", as_string(r.get("id"))),
        ("domain", as_string(r.get("domain"))),
        ("env", as_string(r.get("env"))),
        ("requestId", as_string(r.get("requestId"))),
        ("appId", as_string(r.get("appId"))),
        ("hostname", as_string(r.get("hostname"))),
        ("version", as_string(r.get("version"))),
        ("artifact", as_string(r.get("artifact"))),
        ("start", instant_to_millis(r.get("start"))),
        ("end", instant_to_millis(r.get("end"))),
        ("ttl", int(ttl) if ttl is not None else ttl_default),
        ("indexedKeyValues", None if kvs is None else [OrderedDict([
            ("key", as_string(kv.get("key"))),
            ("value", as_string(kv.get("value"))),
            ("valueInteger", None if kv.get("valueInteger") is None else int(kv.get("valueInteger"))),
            ("valueFloat", None if kv.get("valueFloat") is None else float(kv.get("valueFloat"))),
            ("valueDate", date_to_millis(kv.get("valueDate"))),
        ]) for kv in kvs]),
        ("audits", None if audits is None else [OrderedDict([
            ("type", as_string(a.get("type"))),
            ("events", as_number(a.get("events"))),
        ]) for a in audits]),
    ])


# ---------------------------------------------------------------------------------------------
# OpenSearch
# ---------------------------------------------------------------------------------------------

class OpenSearch:
    def __init__(self, url):
        self.url = url.rstrip("/")

    def request(self, method, path, body=None, content_type="application/json", timeout=120):
        data = body.encode("utf-8") if isinstance(body, str) else body
        req = urllib.request.Request(self.url + path, data=data, method=method,
                                     headers={"Content-Type": content_type})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read() or b"null")
        except urllib.error.HTTPError as e:
            payload = e.read()
            try:
                return e.code, json.loads(payload)
            except ValueError:
                return e.code, {"error": payload.decode("utf-8", "replace")[:500]}

    def health(self):
        return self.request("GET", "/_cluster/health")[1].get("status")

    def day_index(self, prefix, day):
        status, rows = self.request("GET", "/_cat/indices/%s-%s-*?h=index&format=json" % (prefix, day))
        if status != 200 or not rows:
            return None
        return sorted(r["index"] for r in rows)[-1]

    def existing_ids(self, index, ids):
        body = json.dumps({"ids": ids})
        status, resp = self.request("POST", "/%s/_mget?_source=false" % index, body)
        if status != 200:
            raise RuntimeError("_mget %s failed: %s %s" % (index, status, str(resp)[:300]))
        return {d["_id"] for d in resp["docs"] if d.get("found")}

    def bulk_create(self, items):
        """items: [(index, id, doc_json)] -> {(index, id): status}"""
        lines = []
        for index, _id, doc in items:
            lines.append(json.dumps({"create": {"_index": index, "_id": _id}}))
            lines.append(doc)
        status, resp = self.request("POST", "/_bulk", "\n".join(lines) + "\n", "application/x-ndjson")
        if status != 200:
            return {(i, d): status for i, d, _ in items}, resp
        out = {}
        for (index, _id, _), item in zip(items, resp["items"]):
            out[(index, _id)] = item["create"]["status"]
        return out, resp


# ---------------------------------------------------------------------------------------------
# Replay
# ---------------------------------------------------------------------------------------------

def connection_string(path):
    text = open(path).read()
    m = re.search(r'password="([^"]+)"', text)
    if not m:
        sys.exit("No password=\"...\" (Event Hubs connection string) found in %s" % path)
    bootstrap = re.search(r"^bootstrap\.servers=(\S+)", text, re.M)
    return m.group(1), bootstrap.group(1) if bootstrap else None


def parse_time(s):
    return int(dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--from", dest="start", required=True, help="window start, e.g. 2026-11-04T12:00:00Z (enqueue time)")
    p.add_argument("--to", dest="end", required=True, help="window end, exclusive, e.g. 2026-11-04T21:50:00Z")
    p.add_argument("--topic", default="kockpit")
    p.add_argument("--command-config", default="client.properties",
                   help="Kafka client properties holding bootstrap.servers and the SASL password (connection string)")
    p.add_argument("--bootstrap", help="overrides bootstrap.servers from --command-config")
    p.add_argument("--opensearch", default="http://localhost:9200")
    p.add_argument("--index-suffix", default="audit-data")
    p.add_argument("--ttl-default", type=int, default=5, help="ttl for reports without one (stream default: 5)")
    p.add_argument("--only-domain", action="append", help="replay only these domains (repeatable)")
    p.add_argument("--bulk-size", type=int, default=500)
    p.add_argument("--rate", type=float, default=500.0, help="max documents written per second")
    p.add_argument("--check-existing", action="store_true", help="dry run: also count records already indexed (_mget)")
    p.add_argument("--execute", action="store_true", help="write to OpenSearch (default: dry run)")
    p.add_argument("--errors", default="replay-errors.jsonl")
    p.add_argument("--api-version", help="pin the Kafka protocol version, e.g. 2.0.0 (default: negotiated)")
    p.add_argument("--offsets", help="skip the offset lookup: 'partition:start-end,...' (end exclusive), "
                                     "e.g. from kafka-get-offsets.sh --time <ms> for --from and --to")
    args = p.parse_args()

    password, bootstrap = connection_string(args.command_config)
    bootstrap = args.bootstrap or bootstrap
    start_ms, end_ms = parse_time(args.start), parse_time(args.end)
    os_ = OpenSearch(args.opensearch)

    kafka_options = dict(
        bootstrap_servers=bootstrap,
        security_protocol="SASL_SSL", sasl_mechanism="PLAIN",
        sasl_plain_username="$ConnectionString", sasl_plain_password=password,
        group_id=None, enable_auto_commit=False,
        max_poll_records=args.bulk_size, request_timeout_ms=60000,
    )
    if args.api_version:
        kafka_options["api_version"] = tuple(int(x) for x in args.api_version.split("."))
    consumer = KafkaConsumer(**kafka_options)

    plan = []
    if args.offsets:
        for part in args.offsets.split(","):
            partition, span = part.split(":")
            s, e = (int(x) for x in span.split("-"))
            plan.append((TopicPartition(args.topic, int(partition)), s, e))
    else:
        tps = [TopicPartition(args.topic, part) for part in sorted(consumer.partitions_for_topic(args.topic) or [])]
        if not tps:
            sys.exit("topic %s not found" % args.topic)
        consumer.assign(tps)
        starts = consumer.offsets_for_times({tp: start_ms for tp in tps})
        ends = consumer.offsets_for_times({tp: end_ms for tp in tps})
        log_end = consumer.end_offsets(tps)
        for tp in tps:
            s = starts[tp].offset if starts[tp] else log_end[tp]
            e = ends[tp].offset if ends[tp] else log_end[tp]
            plan.append((tp, s, e))
    for tp, s, e in plan:
        print("partition %d: offsets %d -> %d (%d records)" % (tp.partition, s, e, max(0, e - s)))
    print("mode: %s, rate <= %.0f docs/s, bulk %d\n" % ("EXECUTE" if args.execute else "dry run", args.rate, args.bulk_size))

    stats = Counter()
    per_index = Counter()
    index_cache = {}
    errors = open(args.errors, "a")
    last_report = time.time()

    def target(doc, ts_ms):
        prefix = "%s-%s-%s-ttl%sd" % (doc["domain"], args.index_suffix, doc["env"], doc["ttl"])
        day = dt.datetime.fromtimestamp(ts_ms / 1000, dt.timezone.utc).strftime("%Y.%m.%d")
        key = (prefix, day)
        if key not in index_cache:
            index_cache[key] = os_.day_index(prefix, day)
        return index_cache[key], prefix + "-" + day

    def flush(batch):
        if not batch:
            return
        if not args.execute:
            if args.check_existing:
                by_index = {}
                for index, _id, _ in batch:
                    by_index.setdefault(index, []).append(_id)
                for index, ids in by_index.items():
                    found = os_.existing_ids(index, ids)
                    stats["already indexed"] += len(found)
                    stats["missing"] += len(ids) - len(found)
            return
        pending, attempt = batch, 0
        while pending:
            while os_.health() == "red":
                print("cluster is red, pausing 30 s", flush=True)
                time.sleep(30)
            t0 = time.time()
            results, resp = os_.bulk_create(pending)
            retry = []
            for index, _id, doc in pending:
                st = results[(index, _id)]
                if st == 201:
                    stats["created"] += 1
                elif st == 409:
                    stats["exists"] += 1
                elif st == 429 or st >= 500:
                    retry.append((index, _id, doc))
                else:
                    stats["failed"] += 1
                    errors.write(json.dumps({"index": index, "id": _id, "status": st}) + "\n")
            # throttle on what was sent
            min_duration = len(pending) / args.rate
            elapsed = time.time() - t0
            if elapsed < min_duration:
                time.sleep(min_duration - elapsed)
            if retry:
                attempt += 1
                if attempt > 6:
                    stats["failed"] += len(retry)
                    for index, _id, _ in retry:
                        errors.write(json.dumps({"index": index, "id": _id, "status": "gave up after retries"}) + "\n")
                    break
                wait = min(60, 2 ** attempt)
                print("%d items rejected/unavailable, retrying in %d s" % (len(retry), wait), flush=True)
                time.sleep(wait)
            pending = retry

    for tp, s, e in plan:
        if s >= e:
            continue
        consumer.assign([tp])
        consumer.seek(tp, s)
        batch = []
        done = False
        while not done:
            polled = consumer.poll(timeout_ms=10000, max_records=args.bulk_size)
            if not polled:
                if consumer.position(tp) >= e:
                    break
                continue
            for rec in polled.get(tp, []):
                if rec.offset >= e:
                    done = True
                    break
                stats["read"] += 1
                try:
                    report = decode(rec.value)
                    doc = to_doc(report, args.ttl_default)
                except Exception as ex:  # noqa: BLE001 - report and continue
                    stats["undecodable"] += 1
                    errors.write(json.dumps({"partition": tp.partition, "offset": rec.offset, "error": str(ex)[:300]}) + "\n")
                    continue
                if args.only_domain and doc["domain"] not in args.only_domain:
                    stats["other domain"] += 1
                    continue
                _id = doc["id"] or doc["requestId"]
                if not _id:
                    stats["no id"] += 1
                    continue
                index, wanted = target(doc, rec.timestamp)
                if not index:
                    stats["no index for " + wanted] += 1
                    continue
                per_index[index] += 1
                batch.append((index, _id, json.dumps(doc, ensure_ascii=False, separators=(",", ":"))))
                if len(batch) >= args.bulk_size:
                    flush(batch)
                    batch = []
            if consumer.position(tp) >= e:
                done = True
            if time.time() - last_report > 30:
                print("p%d @%d/%d %s" % (tp.partition, consumer.position(tp), e, dict(stats)), flush=True)
                last_report = time.time()
        flush(batch)
        print("partition %d done: %s" % (tp.partition, dict(stats)), flush=True)

    consumer.close()
    errors.close()
    print("\n== per target index")
    for index, n in sorted(per_index.items()):
        print("  %-50s %d" % (index, n))
    print("== totals")
    for k, v in sorted(stats.items()):
        print("  %-50s %d" % (k, v))


if __name__ == "__main__":
    main()
