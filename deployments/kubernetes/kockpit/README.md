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
| `<component>.env`                      | `[]`         | Extra environment variables for a Kockpit application            |
| `<component>.resources`                |              | Requests and limits                                              |
| `ingress.enabled` / `ingress.host`     | `false`      | Ingress to the console                                           |

## Uninstall

```bash
helm uninstall kockpit -n kockpit
kubectl -n kockpit delete pvc kockpit-data   # optional: also delete the stored manifests and payloads
kind delete cluster --name kockpit            # if you created a kind cluster for it
```
