# Kockpit SaaS (authenticated) profile on kind

The chart (`../kockpit`) with `values-public.yaml`: sign-up / sign-in on the console, self-service tenants and API
keys, the HTTP ingestion gateway and PostgreSQL. This directory adds what a local
[kind](https://kind.sigs.k8s.io) cluster needs. For a real internet-facing instance (TLS, DNS), see
[`deployments/ec2`](../../ec2/README.md).

| File | |
|---|---|
| `values-kind.yaml` | kind: no ingress (port-forward), smaller OpenSearch, administrator `admin@kockpit.test` |
| `values-local-images.yaml` | optional: images built from your working copy instead of `ghcr.io/...:latest` |

All commands from the repository root.

## Install

```bash
kind create cluster --name kockpit      # once

helm upgrade --install kockpit deployments/kubernetes/kockpit -n kockpit --create-namespace \
  -f deployments/kubernetes/kockpit/values-public.yaml \
  -f deployments/kubernetes/saas/values-kind.yaml
kubectl -n kockpit wait --for=condition=Ready pod --all --timeout=600s

kubectl -n kockpit port-forward svc/kockpit-console 3000:80 &      # console: http://localhost:3000
kubectl -n kockpit port-forward svc/kockpit-gateway 18080:8080 &   # gateway: http://localhost:18080/ingest/v1/...

# administrator admin@kockpit.test, password:
kubectl -n kockpit get secret kockpit-secrets -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

An application sends its audits with `kockpit.gateway.url: http://localhost:18080` and a key created on the
console's "Tenant & API keys" page (the page shows "Ingestion gateway: not configured": no ingress, no public URL).

## With images built from your working copy

```bash
mvn -q package -Pfilesystem -pl kockpit-core-applications/kockpit-backend-application -am -Dmaven.test.skip=true
mvn -q package -Pkafka -pl kockpit-core-applications/kockpit-audit-stream/kockpit-audit-stream-application -am -Dmaven.test.skip=true
mvn -q package -pl kockpit-core-applications/kockpit-gateway-application -am -Dmaven.test.skip=true

docker build -t kockpit-local/backend-filesystem:dev kockpit-core-applications/kockpit-backend-application
docker build -t kockpit-local/audit-stream-kafka:dev kockpit-core-applications/kockpit-audit-stream/kockpit-audit-stream-application
docker build -t kockpit-local/gateway:dev kockpit-core-applications/kockpit-gateway-application
docker build -t kockpit-local/console:dev kockpit-core-applications/kockpit-console
for i in backend-filesystem audit-stream-kafka gateway console; do kind load docker-image kockpit-local/$i:dev --name kockpit; done

helm upgrade --install kockpit deployments/kubernetes/kockpit -n kockpit --create-namespace \
  -f deployments/kubernetes/kockpit/values-public.yaml \
  -f deployments/kubernetes/saas/values-kind.yaml \
  -f deployments/kubernetes/saas/values-local-images.yaml
```

After a code change: rebuild the image, `kind load` it again, then
`kubectl -n kockpit rollout restart deploy/kockpit-<backend|gateway|console|audit-stream>`.

## Remove

```bash
helm uninstall kockpit -n kockpit
kubectl delete namespace kockpit        # the volumes and the secret, kept by uninstall on purpose
```

Delete the namespace before installing the quick start (the chart without `values-public.yaml`) in it: the
volumes kept from this profile are larger than the quick start's, and Kubernetes refuses to shrink them
("spec.resources.requests.storage: Forbidden: field can not be less than status.capacity").
