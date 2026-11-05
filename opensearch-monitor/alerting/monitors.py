#!/usr/bin/env python3
"""Manage the kockpit OpenSearch Alerting monitors and their Teams channel.

The cluster is private: run this through a tunnel (default http://localhost:9200).

  monitors.py check            dry-run every monitor: shows which triggers fire and the rendered
                               Teams card. Saves nothing, sends nothing.
  monitors.py apply            create/update the Teams channel and the monitors (idempotent, by name).
                               Needs TEAMS_WEBHOOK_URL (a Teams Workflows "Post to a channel when a
                               webhook request is received" URL).
  monitors.py test             send one sample card through the channel created by `apply`.

Monitor definitions live in monitors/*.json. Each trigger has an `id`; its `script` must store a
one-line description of what it found in ctx.results[0].<id>, which the Teams card displays.
A trigger may set "throttle_minutes" (default 60). Placeholders: __ENV__, __EXPECTED_NODES__.

Ingestion monitors are generated from templates/ingestion.json, one per --ingestion DOMAIN:MINUTES
(e.g. --ingestion rcu:30 alerts when the newest audit in rcu-audit-data-<env>-read is 30 minutes old).
Pick domains with steady traffic: the others can be silent for hours.
"""
import argparse
import glob
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
THROTTLE_MINUTES = 60


def call(base, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")
    except urllib.error.URLError as e:
        raise SystemExit("cannot reach %s (%s): is the tunnel open?" % (base, e.reason))


def channel_id(env):
    return "kockpit-teams-" + env


def card(env, trigger_id, throttle=THROTTLE_MINUTES):
    """Adaptive Card as a mustache template. The state is spelled out, never conveyed by color alone."""
    return json.dumps({
        "type": "message",
        "attachments": [{
            "contentType": "application/vnd.microsoft.card.adaptive",
            "content": {
                "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                "type": "AdaptiveCard",
                "version": "1.4",
                "msteams": {"width": "Full"},
                "body": [
                    {"type": "TextBlock", "size": "Large", "weight": "Bolder", "wrap": True,
                     "text": "🔥 FIRED · %s · {{ctx.trigger.name}}" % env.upper()},
                    {"type": "TextBlock", "wrap": True, "text": "{{ctx.results.0.%s}}" % trigger_id},
                    {"type": "TextBlock", "wrap": True, "text": "{{#ctx.error}}Monitor error: {{ctx.error}}{{/ctx.error}}"},
                    {"type": "FactSet", "facts": [
                        {"title": "Monitor", "value": "{{ctx.monitor.name}}"},
                        {"title": "Severity", "value": "{{ctx.trigger.severity}} (1 = highest)"},
                        {"title": "Checked at", "value": "{{ctx.periodEnd}}"},
                        {"title": "Repeats", "value": "every %d min while active (no resolve message)" % throttle},
                    ]},
                ],
            },
        }],
    }, ensure_ascii=False)


def parse_ingestion(value):
    domain, _, minutes = value.partition(":")
    if not domain or not minutes.isdigit() or int(minutes) < 1:
        raise argparse.ArgumentTypeError("expected DOMAIN:MINUTES, e.g. rcu:30")
    return domain, int(minutes)


def load_monitors(args):
    def render(path, extra=()):
        with open(path) as f:
            raw = f.read()
        for key, value in (("__ENV__", args.env), ("__EXPECTED_NODES__", str(args.expected_nodes)),
                           ("__INDEX__", args.index)) + tuple(extra):
            raw = raw.replace(key, value)
        return json.loads(raw)

    monitors = [render(path) for path in sorted(glob.glob(os.path.join(HERE, "monitors", "*.json")))]
    for domain, minutes in args.ingestion:
        monitors.append(render(os.path.join(HERE, "templates", "ingestion.json"),
                               (("__DOMAIN__", domain), ("__MAX_LAG__", str(minutes)))))
    return monitors


def build(spec, env):
    return {
        "type": "monitor",
        "monitor_type": spec["monitor_type"],
        "name": spec["name"],
        "enabled": True,
        "schedule": {"period": {"interval": spec["interval_minutes"], "unit": "MINUTES"}},
        "inputs": spec["inputs"],
        "triggers": [{
            "name": t["name"],
            "severity": t["severity"],
            "condition": {"script": {"lang": "painless", "source": t["script"]}},
            "actions": [{
                "name": "teams",
                "destination_id": channel_id(env),
                "message_template": {"lang": "mustache", "source": card(env, t["id"], t.get("throttle_minutes", THROTTLE_MINUTES))},
                "throttle_enabled": True,
                "throttle": {"value": t.get("throttle_minutes", THROTTLE_MINUTES), "unit": "MINUTES"},
            }],
        } for t in spec["triggers"]],
    }


def execute(base, monitor, dryrun=True):
    status, res = call(base, "POST", "/_plugins/_alerting/monitors/_execute?dryrun=%s" % str(dryrun).lower(), monitor)
    if status != 200:
        raise SystemExit("execute %s failed (%s): %s" % (monitor["name"], status, json.dumps(res)[:500]))
    return res


def cmd_check(args):
    ok = True
    for spec in load_monitors(args):
        res = execute(args.url, build(spec, args.env))
        err = (res.get("input_results") or {}).get("error")
        print("== %s%s" % (spec["name"], ("  INPUT ERROR: %s" % err) if err else ""))
        ok &= not err
        names = {t["name"]: t["id"] for t in spec["triggers"]}
        for tr in res.get("trigger_results", {}).values():
            if tr.get("error"):
                ok = False
                print("   ERROR    %s: %s" % (tr["name"], tr["error"]))
                continue
            state = "FIRES" if tr.get("triggered") else "ok"
            line = "   %-8s %s" % (state, tr["name"])
            for action in tr.get("action_results", {}).values():
                message = (action.get("output") or {}).get("message", "")
                try:
                    body = json.loads(message)["attachments"][0]["content"]["body"]
                    detail = body[1]["text"]
                except (ValueError, KeyError, IndexError):
                    ok = False
                    detail = "rendered card is not valid JSON: %r" % message[:300]
                if tr.get("triggered") or detail.startswith("rendered"):
                    line += "\n            -> " + detail
                if action.get("error"):
                    ok = False
                    line += "\n            action error: %s" % action["error"]
            print(line)
        unknown = set(names) - {tr["name"] for tr in res.get("trigger_results", {}).values()}
        if unknown:
            ok = False
            print("   not evaluated: %s" % ", ".join(sorted(unknown)))
    return 0 if ok else 1


def find_monitor(base, name):
    status, res = call(base, "POST", "/_plugins/_alerting/monitors/_search",
                        {"size": 10, "query": {"term": {"monitor.name.keyword": name}}})
    if status != 200:
        if status == 404:  # alerting config index does not exist yet
            return None
        raise SystemExit("monitor search failed (%s): %s" % (status, res))
    hits = res.get("hits", {}).get("hits", [])
    if len(hits) > 1:
        raise SystemExit("%d monitors are named %s: clean up by hand first" % (len(hits), name))
    return hits[0]["_id"] if hits else None


def cmd_apply(args):
    url = os.environ.get("TEAMS_WEBHOOK_URL")
    if not url:
        raise SystemExit("set TEAMS_WEBHOOK_URL")
    cid = channel_id(args.env)
    config = {"name": "Kockpit Teams alerts (%s)" % args.env, "description": "Managed by kockpit-tf/opensearch-monitors",
              "config_type": "webhook", "is_enabled": True,
              "webhook": {"url": url, "method": "POST", "header_params": {"Content-Type": "application/json"}}}
    status, _ = call(args.url, "GET", "/_plugins/_notifications/configs/" + cid)
    if status == 200:
        status, res = call(args.url, "PUT", "/_plugins/_notifications/configs/" + cid, {"config": config})
        action = "updated"
    else:
        status, res = call(args.url, "POST", "/_plugins/_notifications/configs", {"config_id": cid, "config": config})
        action = "created"
    if status not in (200, 201):
        raise SystemExit("channel %s failed (%s): %s" % (cid, status, res))
    print("channel %s %s" % (cid, action))

    for spec in load_monitors(args):
        body = build(spec, args.env)
        existing = find_monitor(args.url, spec["name"])
        if existing:
            status, res = call(args.url, "PUT", "/_plugins/_alerting/monitors/" + existing, body)
            action = "updated"
        else:
            status, res = call(args.url, "POST", "/_plugins/_alerting/monitors", body)
            action = "created"
        if status not in (200, 201):
            raise SystemExit("monitor %s failed (%s): %s" % (spec["name"], status, json.dumps(res)[:500]))
        print("monitor %s %s (%s)" % (spec["name"], action, res.get("_id")))
    return 0


def cmd_test(args):
    probe = {
        "type": "monitor", "monitor_type": "cluster_metrics_monitor", "name": "kockpit-teams-test", "enabled": False,
        "schedule": {"period": {"interval": 1, "unit": "MINUTES"}},
        "inputs": [{"uri": {"api_type": "CLUSTER_HEALTH", "path": "_cluster/health", "path_params": "",
                            "url": "http://localhost:9200/_cluster/health"}}],
        "triggers": [{
            "name": "Test message - please ignore", "severity": "5",
            "condition": {"script": {"lang": "painless", "source":
                "def h = ctx.results[0]; h.test = 'Test from monitors.py · cluster ' + h.cluster_name + ' is ' + h.status + ' with ' + h.number_of_nodes + ' nodes'; return true;"}},
            "actions": [{"name": "teams", "destination_id": channel_id(args.env),
                         "message_template": {"lang": "mustache", "source": card(args.env, "test")},
                         "throttle_enabled": False}],
        }],
    }
    res = execute(args.url, probe, dryrun=False)  # unsaved monitor, but actions really run
    for tr in res.get("trigger_results", {}).values():
        for action in tr.get("action_results", {}).values():
            if action.get("error"):
                print("send failed: %s" % action["error"])
                return 1
    print("sent: check the Teams channel")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["check", "apply", "test"])
    p.add_argument("--url", default="http://localhost:9200")
    p.add_argument("--env", required=True, help="environment of the cluster, as in the index names (dev, pro...)")
    p.add_argument("--expected-nodes", type=int, default=5)
    p.add_argument("--index", default="audit-data", help="index name between domain and env (default audit-data)")
    p.add_argument("--ingestion", type=parse_ingestion, action="append", default=[], metavar="DOMAIN:MINUTES",
                   help="add an ingestion monitor for DOMAIN, alerting after MINUTES without a new audit (repeatable)")
    args = p.parse_args()
    args.url = args.url.rstrip("/")
    return {"check": cmd_check, "apply": cmd_apply, "test": cmd_test}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
