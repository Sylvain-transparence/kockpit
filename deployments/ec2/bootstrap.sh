#!/usr/bin/env bash
# Installs a shared Kockpit instance on this machine (an EC2 instance, Ubuntu 24.04): k3s, cert-manager with a
# Let's Encrypt issuer, then the Kockpit chart with values-public.yaml. Run from a clone of the repository:
#
#   sudo ./deployments/ec2/bootstrap.sh console.example.com ingest.example.com admin@example.com [acme-email]
#
# Both host names must already resolve to this instance (Elastic IP), or the certificates can't be issued.
# Safe to run again: every step installs or upgrades.
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: sudo $0 <console-host> <ingest-host> <admin-email> [acme-email]" >&2
  exit 2
fi
if [[ $EUID -ne 0 ]]; then
  echo "run it with sudo" >&2
  exit 2
fi

CONSOLE_HOST=$1
INGEST_HOST=$2
ADMIN_EMAIL=$3
ACME_EMAIL=${4:-$3}
HERE=$(cd "$(dirname "$0")" && pwd)
CHART=$(cd "$HERE/../kubernetes/kockpit" && pwd)
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

echo "==> DNS"
PUBLIC_IP=$(TOKEN=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60') \
  && curl -sf -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4 || true)
for host in "$CONSOLE_HOST" "$INGEST_HOST"; do
  resolved=$(getent ahostsv4 "$host" | awk 'NR==1 {print $1}' || true)
  if [[ -z "$resolved" ]]; then
    echo "  ⚠️  $host doesn't resolve yet: certificates will be issued once it does"
  elif [[ -n "$PUBLIC_IP" && "$resolved" != "$PUBLIC_IP" ]]; then
    echo "  ⚠️  $host resolves to $resolved, not to this instance ($PUBLIC_IP)"
  else
    echo "  $host -> $resolved"
  fi
done

echo "==> Kernel settings (OpenSearch)"
echo 'vm.max_map_count=262144' > /etc/sysctl.d/99-kockpit.conf
sysctl -q --system

echo "==> k3s"
mkdir -p /var/lib/rancher/k3s/server/manifests
cp "$HERE/k3s/traefik-config.yaml" /var/lib/rancher/k3s/server/manifests/traefik-config.yaml
if ! command -v k3s >/dev/null; then
  curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--write-kubeconfig-mode 600" sh -
fi
until kubectl get nodes 2>/dev/null | grep -q ' Ready'; do sleep 3; done
kubectl get nodes

echo "==> Helm"
if ! command -v helm >/dev/null; then
  snap install helm --classic
fi

echo "==> cert-manager"
helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace \
  --set crds.enabled=true --wait
sed "s|ACME_EMAIL|$ACME_EMAIL|" "$HERE/k3s/cluster-issuer.yaml" | kubectl apply -f -

echo "==> Kockpit"
helm upgrade --install kockpit "$CHART" -n kockpit --create-namespace \
  -f "$CHART/values-public.yaml" \
  --set ingress.host="$CONSOLE_HOST" \
  --set ingress.gatewayHost="$INGEST_HOST" \
  --set auth.local.adminEmail="$ADMIN_EMAIL"

cat <<EOF

Kockpit is starting (a few minutes; the gateway waits for the backend to create the database):
  sudo kubectl -n kockpit get pods -w
  sudo kubectl -n kockpit get certificates

Console:  https://$CONSOLE_HOST   (administrator $ADMIN_EMAIL)
Gateway:  https://$INGEST_HOST/ingest/v1/audits

The administrator's password:
  sudo kubectl -n kockpit get secret kockpit-secrets -o jsonpath='{.data.admin-password}' | base64 -d; echo
EOF
