# Kockpit ingestion gateway

Public HTTP entry point for applications outside the cluster. Every call carries a tenant API key
(created in the console, "Tenant & API keys"): `X-Kockpit-Api-Key: kpk_...` (or `Authorization: Bearer kpk_...`).
The key decides the domain (its tenant) and the allowed envs, whatever the payload says.

| Call | Body | Answer |
|---|---|---|
| `POST /ingest/v1/audits` | an audit or an array of audits (SDK `AuditReport` JSON), optionally `Content-Encoding: gzip` | `202 {accepted, remaining, quota}` |
| `POST /ingest/v1/messages` | one message (heartbeat, feature-flipping / dyna-config acknowledgement...) | `202 {id}` |
| `GET /ingest/v1/messages?env=&appId=&service=&type=` | | the env's messages, newest first; `appId`, `service` and `type` filter when given |

Audits:
- `domain` is replaced by the key's tenant, `ttl` by `KOCKPIT_GATEWAY_AUDIT_TTL_DAYS` (default 7);
- `env` must be one of the tenant's envs, `appId` is required, `id` is generated when missing;
- all or nothing: an invalid audit, or a batch larger than what's left of the daily quota, refuses
  the whole batch (400 / 429) and counts nothing;
- quota per key and UTC day: `X-Quota-Limit` / `X-Quota-Remaining` on every answer, 429 with
  `Retry-After` (seconds to midnight UTC) once reached;
- delivered to Kafka (topic `NOTIFICATION_TOPIC`, key `domain-env-appId`) before answering: 503
  (nothing counted) when Kafka doesn't acknowledge.

Limits: 500 audits and 1 MiB (uncompressed) per request, 64 KiB per message, 1200 messages per key
per hour, 30 unknown keys per client IP per minute; a revoked key stops working within 30 s.

Configuration: `KOCKPIT_DB_URL|USERNAME|PASSWORD` (the backend's local accounts database, migrated
by the backend), `BOOTSTRAP_SERVERS`, `NOTIFICATION_TOPIC`, `FS_STORAGE_PATH` (the directory the
backend polls for messages). Management (health) on port 8091.
