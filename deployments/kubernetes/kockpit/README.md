# Kockpit Helm chart (quick start)

Deploys the same stack as `docker/kafka/docker-compose-full.yml`, plus the sample application, on a Kubernetes
cluster. It targets a **single-node local cluster** (kind, minikube, Docker Desktop):

| Component      | Kind        | Image                                                                   | Port |
|----------------|-------------|-------------------------------------------------------------------------|------|
| `kafka`        | StatefulSet | `apache/kafka` (KRaft, single node)                                     | 9092 |
| `opensearch`   | StatefulSet | `opensearchproject/opensearch` (security plugin disabled)               | 9200 |
| `dashboards`   | Deployment  | `opensearchproject/opensearch-dashboards` (off by default)              | 5601 |
| `audit-stream` | Deployment  | `ghcr.io/mahieddine-ichir/kockpit/kockpit-audit-stream-application-kafka` | 8081 |
| `backend`      | Deployment  | `ghcr.io/mahieddine-ichir/kockpit/kockpit-backend-application-filesystem` | 8080 |
| `console`      | Deployment  | `ghcr.io/mahieddine-ichir/kockpit/kockpit-console`                      | 80   |
| `sample-app`   | Deployment  | `ghcr.io/mahieddine-ichir/kockpit/kockpit-sample-all-filesystem`        | 8082 |

The Kockpit images use the `latest` tag and are pulled on every pod start (`image.tag`, `image.pullPolicy`).

## Install

Prerequisites: a cluster with about 6 GB of memory available, `kubectl` and Helm 3 or later. With kind:

```bash
kind create cluster --name kockpit
helm install kockpit deployments/kubernetes/kockpit --namespace kockpit --create-namespace
kubectl -n kockpit get pods -w
```

Then follow the notes printed by `helm install` (`helm get notes kockpit -n kockpit`):

```bash
kubectl -n kockpit port-forward svc/kockpit-console 3000:80     # console: http://localhost:3000
kubectl -n kockpit port-forward svc/kockpit-sample-app 8082:8082 # sample app API
```

## Storage

- **Manifests**: `files/manifests/*.json` (and `manifests.extra`) go into a ConfigMap that the backend's init
  container copies into its storage on every start, **overwriting** the stored copies: manifest changes saved from
  the console are lost when the backend restarts. Keep `files/manifests/kockpit-manifest-local.json` in sync with
  `docker/manifests/kockpit-manifest-local.json`.
- **Filesystem storage** (`data.persistence`): a ReadWriteOnce PVC shared by the backend and the sample application,
  so both pods must run on the same node. It is kept on `helm uninstall` (`helm.sh/resource-policy: keep`); delete it
  with `kubectl -n kockpit delete pvc kockpit-data`.
- **Kafka and OpenSearch** use `emptyDir` by default (data lost when their pod restarts). Set
  `kafka.persistence.enabled` / `opensearch.persistence.enabled` to keep it.

## Main values

| Value                                  | Default      | Description                                                      |
|----------------------------------------|--------------|------------------------------------------------------------------|
| `image.registry` / `image.tag`         | GHCR, latest | Registry and tag of the Kockpit images                           |
| `indexName`                            | `auditdata`  | Index name shared by the audit stream and the backend            |
| `auditTopic`                           | `audits`     | Kafka topic of the audits                                        |
| `kafka.enabled`                        | `true`       | `false` + `kafka.externalBootstrapServers` to use your own Kafka |
| `opensearch.enabled`                   | `true`       | `false` + `opensearch.externalUrl` to use your own OpenSearch    |
| `dashboards.enabled`                   | `false`      | OpenSearch Dashboards                                            |
| `sampleApp.enabled`                    | `true`       | Sample application generating audits                             |
| `sampleApp.notificationDelayMs`        | `5000`       | How often the sample app sends its buffered audits               |
| `backend.pollInterval`                 | `PT5S`       | How often the backend reads the shared storage (heartbeats, acks) |
| `console.flagsRefreshMs`               | `5000`       | Refresh of the feature-flipping page (instances' acks), in ms    |
| `sampleApp.pollInterval`               | `PT5S`       | How often the sample app reads flag and configuration values     |
| `<component>.env`                      | `[]`         | Extra environment variables for a Kockpit application            |
| `<component>.resources`                |              | Requests and limits                                              |
| `ingress.enabled` / `ingress.host`     | `false`      | Ingress to the console (and `/api` to the backend)               |
| `ingress.gatewayHost`                  | `""`         | Host of the ingestion gateway's `/ingest`                        |
| `ingress.certManagerClusterIssuer`     | `""`         | cert-manager ClusterIssuer: one TLS certificate per host         |

## Shared instance (accounts, tenants, API keys)

`values-public.yaml` turns the quick start into an instance that external testers use from the internet:

| Component  | Kind        | Image                                                       | Port |
|------------|-------------|-------------------------------------------------------------|------|
| `postgres` | StatefulSet | `postgres:17-alpine`: accounts, tenants, API keys, usage    | 5432 |
| `gateway`  | Deployment  | `ghcr.io/mahieddine-ichir/kockpit/kockpit-gateway-application` | 8080 |

- `auth.local.enabled`: sign-up and sign-in on the console, every user sees only their tenants' manifests,
  the administrator (`auth.local.adminEmail`) sees everything. Requires `postgres.enabled` and an ingress host
  (CORS is limited to the console's origin, cookies are `Secure` with TLS).
- `gateway.enabled`: `https://<ingress.gatewayHost>/ingest/v1/...`, see the gateway's README. It shares the data
  volume with the backend (messages) and waits for the backend to create the database tables.
- Secrets: `<release>-secrets` holds `db-password`, `jwt-secret` and `admin-password`, random at install and kept
  across upgrades and `helm uninstall` (with the PostgreSQL volume). Set `auth.existingSecret` to bring your own.
- No sample app, no demo manifest, persistent Kafka and OpenSearch.

```bash
helm upgrade --install kockpit deployments/kubernetes/kockpit -n kockpit --create-namespace \
  -f deployments/kubernetes/kockpit/values-public.yaml \
  --set ingress.host=console.example.com --set ingress.gatewayHost=ingest.example.com \
  --set auth.local.adminEmail=you@example.com
```

On a single EC2 instance with k3s, Traefik and Let's Encrypt: [`deployments/ec2`](../../ec2/README.md).

## Uninstall

```bash
helm uninstall kockpit -n kockpit
kubectl -n kockpit delete pvc kockpit-data   # optional: also delete the stored manifests and payloads
# shared instance: the accounts database and its password are kept too
kubectl -n kockpit delete pvc data-kockpit-postgres-0 && kubectl -n kockpit delete secret kockpit-secrets
kind delete cluster --name kockpit            # if you created a kind cluster for it
```
