# OpenSearch alerting

Cluster-level alerting with the OpenSearch Alerting plugin, posting to Microsoft Teams. It complements the
dashboard in `../` (which only displays the cluster's state) and the host-level Azure Monitor alerts (disk,
memory, heartbeat, OOM kill) of the Terraform module `deployments/azure/modules/opensearch-alerting`.

Clusters are usually private, so this is applied by hand through a tunnel to port 9200 (no pipeline):

```bash
# dry run: what fires right now, and the card text; saves and sends nothing
python3 -I monitors.py check --env pro --ingestion rcu:30

# create/update the Teams channel and the monitors (idempotent, by name)
TEAMS_WEBHOOK_URL='https://…' python3 -I monitors.py apply --env pro --ingestion rcu:30

# send one sample card to Teams
python3 -I monitors.py test --env pro
```

Options: `--env` (required: the environment in the index names), `--ingestion DOMAIN:MINUTES` (repeatable),
`--index` (default `audit-data`), `--expected-nodes` (default 5), `--url` (default `http://localhost:9200`).

`TEAMS_WEBHOOK_URL` is the URL of a Teams Workflows flow "Post to a channel when a webhook request is
received", the same one as the `teams_webhook_url` of the Terraform module. Never commit it.

| Monitor | Every | Triggers |
|---|---|---|
| `kockpit-cluster-health` | 2 min | cluster red (sev1), node missing (sev1), unassigned shards (sev2) |
| `kockpit-node-resources` | 5 min | data disk > 88% (sev1), old gen > 75% after GC (sev2), rejections / breaker trips since node start (sev2, daily), write queue > 200 (sev3) |
| `kockpit-indices` | 15 min | concrete index named `*-write`/`*-read` (sev1), red index (sev1), index > 50 GB primary (sev2) |
| `kockpit-<domain>-ingestion`, one per `--ingestion` | 10 min | newest audit of `<domain>-<index>-<env>-read` older than MINUTES (sev1) |

Only give `--ingestion` for domains with steady traffic: the others can be silent for hours and would alert
for nothing.

## Writing monitors

- `monitors/*.json` are applied as they are; `templates/ingestion.json` once per `--ingestion`.
  Placeholders: `__ENV__`, `__EXPECTED_NODES__`, `__INDEX__`, and `__DOMAIN__`/`__MAX_LAG__` in the template.
- Each trigger has an `id`; its painless `script` stores a one-line description of what it found in
  `ctx.results[0].<id>`, which the Teams card shows. A trigger may set `throttle_minutes` (default 60).
- An active alert re-notifies every `throttle_minutes`. OpenSearch Alerting sends no "resolved" message:
  acknowledge it, or wait for it to clear, in Dashboards → Alerting.
- Date math (`now-30m`, `{{period_end}}||-30m`) in a monitor's search matched nothing on OpenSearch 3.2:
  compare timestamps in the trigger script against `ctx.periodEnd.toEpochMilli()` instead.
