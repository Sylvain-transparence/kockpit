# Kockpit audits over HTTPS (ingestion gateway)

For an application outside the platform's network: audits go to the Kockpit ingestion gateway
with a tenant's API key, created on the console's **Tenant & API keys** page.

```xml
<dependency>
    <groupId>org.kockpit</groupId>
    <artifactId>kockpit-features-audit-starters-http</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>
<!-- optional: heartbeats, feature flags and dynamic configuration through the gateway too -->
<dependency>
    <groupId>org.kockpit</groupId>
    <artifactId>kockpit-core-communication-http</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>
```

```yaml
kockpit:
  domain: acme            # the tenant (the gateway sets it anyway)
  env: dev                # one of the tenant's envs, else the gateway refuses the audits
  app-id: ${spring.application.name}
  gateway:
    url: https://ingest.example.com
    api-key: ${KOCKPIT_API_KEY}   # from the environment, never in source control
```

The application fails to start when `kockpit.gateway.url` or `kockpit.gateway.api-key` is missing.

## What happens to the audits

Audits are buffered (`kockpit.audit.notification.*`, as with the other starters) and sent as gzip
JSON arrays of at most 500 audits / 1 MB. They are dropped rather than piled up in memory:

| Gateway answer | Client behaviour |
|---|---|
| 202 | sent |
| 429, daily quota used up | dropped until the quota resets (midnight UTC), logged once |
| 401/403, key unknown or revoked | dropped for 5 minutes, then tried again; logged once per pause |
| 400/413, audits refused (env not the tenant's...) | that batch dropped, logged at most every 5 minutes |
| 5xx, unreachable | retried twice (1 s, 2 s), then dropped and paused for 30 s |

When sending resumes, one log line gives the count of audits dropped during the pause.

## Settings

| Property | Default | |
|---|---|---|
| `kockpit.gateway.connect-timeout` | `5s` | |
| `kockpit.gateway.request-timeout` | `10s` | per request |
| `kockpit.audit.http.enabled` | `true` | |
| `kockpit.audit.notification.http.max-batch` | `500` | audits per request |
| `kockpit.audit.notification.http.max-body-bytes` | `1000000` | uncompressed bytes per request |
| `kockpit.audit.notification.http.max-retries` | `2` | |
| `kockpit.audit.notification.http.retry-backoff` | `1s` | doubled on the second retry |
| `kockpit.audit.notification.http.unavailable-pause` | `30s` | |
| `kockpit.audit.notification.http.rejected-key-pause` | `5m` | |
| `kockpit.audit.notification.http.refused-log-interval` | `5m` | |
| `kockpit.communication.http.enabled` | `true` | messages transport |
| `kockpit.communication.http.rejected-key-pause` | `5m` | messages and polling |

The sample application has an `http` Maven profile:
`mvn package -Phttp -pl kockpit-samples/kockpit-sample-all -am`, then run it with
`KOCKPIT_DOMAIN`, `KOCKPIT_ENV`, `KOCKPIT_GATEWAY_URL` and `KOCKPIT_API_KEY`.
