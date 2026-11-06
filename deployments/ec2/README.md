# A shared Kockpit instance on one EC2 instance

For external testers: they sign up on the console, create their tenant and API keys, add the HTTP starter to their
applications (`kockpit-features-audit-starters-http`) and see their audits in the console.

```
                  ┌───────────────────── EC2 t3.large, k3s ─────────────────────┐
 browser ──443──▶ │ Traefik ─ console.<domain>  /     ─▶ console (nginx)        │
                  │         └ console.<domain>  /api  ─▶ backend ─┬─ PostgreSQL │
 apps ────443──▶ │ Traefik ─ ingest.<domain>   /ingest ▶ gateway ─┤  (accounts, │
                  │                                               │   keys)     │
                  │   gateway ─▶ Kafka ─▶ audit stream ─▶ OpenSearch ◀─ backend │
                  └─────────────────────────────────────────────────────────────┘
```

Only ports 80 and 443 are open to the internet, and only the console, `/api` and `/ingest` are routed. Kafka,
OpenSearch (security plugin off), PostgreSQL and the management ports are reachable inside the cluster only.

## 1. Images

The chart pulls `ghcr.io/mahieddine-ichir/kockpit/*:latest`, built by the GitHub workflows on the default branch:
`kockpit-backend-application-filesystem`, `kockpit-console`, `kockpit-audit-stream-application-kafka` and
`kockpit-gateway-application` (`gateway-docker-build.yml`). Check they exist and are public (or set
`imagePullSecrets`) before installing.

## 2. Instance

| | |
|---|---|
| Type | `t3.large` (2 vCPU, 8 GiB): the stack takes about 6 GiB. Burstable: with "unlimited" credits, sustained CPU is billed extra |
| AMI | Ubuntu Server 24.04 LTS (x86_64) |
| Disk | 40 GiB gp3 (OpenSearch 20 GiB, Kafka 5 GiB, PostgreSQL 2 GiB, images) |
| Security group | inbound 80 and 443 from `0.0.0.0/0` (and `::/0`); 22 from your IP only. Never 6443, 9092, 9200, 5432 |
| Address | an Elastic IP, so the DNS records survive a stop/start |

With the AWS CLI (adapt the region, key pair, subnet and your IP):

```bash
REGION=eu-west-1
MY_IP=$(curl -s https://checkip.amazonaws.com)/32
VPC=$(aws ec2 describe-vpcs --region $REGION --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SG=$(aws ec2 create-security-group --region $REGION --vpc-id $VPC --group-name kockpit \
      --description "Kockpit: HTTPS public, SSH from admin" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --region $REGION --group-id $SG --ip-permissions \
  "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}],Ipv6Ranges=[{CidrIpv6=::/0}]" \
  "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}],Ipv6Ranges=[{CidrIpv6=::/0}]" \
  "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MY_IP}]"
AMI=$(aws ssm get-parameter --region $REGION \
      --name /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id --query Parameter.Value --output text)
INSTANCE=$(aws ec2 run-instances --region $REGION --image-id $AMI --instance-type t3.large --key-name <your-key-pair> \
      --security-group-ids $SG --metadata-options HttpTokens=required \
      --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=40,VolumeType=gp3,Encrypted=true}' \
      --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=kockpit}]' --query 'Instances[0].InstanceId' --output text)
ALLOC=$(aws ec2 allocate-address --region $REGION --query AllocationId --output text)
aws ec2 wait instance-running --region $REGION --instance-ids $INSTANCE
aws ec2 associate-address --region $REGION --instance-id $INSTANCE --allocation-id $ALLOC
aws ec2 describe-addresses --region $REGION --allocation-ids $ALLOC --query 'Addresses[0].PublicIp' --output text
```

## 3. DNS

Two `A` records to the Elastic IP, e.g. `console.kockpit.example.com` and `ingest.kockpit.example.com`. Wait until
they resolve: Let's Encrypt checks them over HTTP when issuing the certificates.

## 4. Install

```bash
ssh ubuntu@<elastic-ip>
git clone https://github.com/mahieddine-ichir/kockpit.git && cd kockpit
sudo ./deployments/ec2/bootstrap.sh console.kockpit.example.com ingest.kockpit.example.com you@example.com
```

The script installs k3s (with `traefik-config.yaml`: client IPs kept, HTTP redirected to HTTPS), cert-manager and
the `letsencrypt` ClusterIssuer, then the chart with `values-public.yaml`. It can be run again to upgrade.

Wait until every pod is ready and both certificates are `True`:

```bash
sudo kubectl -n kockpit get pods
sudo kubectl -n kockpit get certificates
sudo kubectl -n kockpit get secret kockpit-secrets -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

## 5. Check

```bash
curl -sI https://console.kockpit.example.com | head -1                       # 200
curl -s https://console.kockpit.example.com/api/me                           # 401: signed out
curl -s -X POST https://ingest.kockpit.example.com/ingest/v1/audits -d '{}'  # 401 missing_api_key
curl -s -o /dev/null -w '%{http_code}\n' https://ingest.kockpit.example.com/actuator/health   # 404: not routed
```

Then sign in as the administrator, sign up a test account in a private window, create its tenant and a key, and
send an audit:

```bash
curl -s -X POST https://ingest.kockpit.example.com/ingest/v1/audits \
  -H "X-Kockpit-Api-Key: $KOCKPIT_API_KEY" -H 'Content-Type: application/json' \
  -d '{"env":"dev","appId":"curl","type":"http","httpMethod":"GET","httpStatus":200}'
```

It shows up in the console (domain = the tenant) within a few seconds.

## What testers get

- an account (sign-up on, at most 5 per IP and 50 overall per hour), **one tenant** with up to 3 envs
  (`dev`, `staging`, `prod`), **3 active keys**, **10,000 audits per key per day**: `auth.tenants.*` in
  `values-public.yaml`. The administrator changes a key's quota on the console's "Tenant & API keys" page.
- **7 days** of audit retention (`gateway.auditTtlDays`): the gateway sets every audit's `ttl`, from which the audit
  stream creates the indices and their OpenSearch retention policy.
- To close sign-ups: `--set auth.local.signupEnabled=false` (existing accounts keep working).

## Operations

- **Upgrade** to the latest images: `sudo kubectl -n kockpit rollout restart deploy` (images are pulled on every
  start), or run `bootstrap.sh` again after a `git pull` for chart changes.
- **Data** lives in k3s' local volumes (`/var/lib/rancher/k3s/storage`) on the root EBS volume: snapshot it
  (Data Lifecycle Manager) to back up accounts, keys and audits. The secret `kockpit-secrets` and the volumes are
  kept on `helm uninstall`; keep the secret if you keep the PostgreSQL volume (its password is in it).
- **kubectl from your machine**: `ssh -L 6443:127.0.0.1:6443 ubuntu@<elastic-ip>`, then copy
  `/etc/rancher/k3s/k3s.yaml` (server `https://127.0.0.1:6443`). Never open 6443 in the security group.
- **Logs**: `sudo kubectl -n kockpit logs deploy/kockpit-gateway` (quota refusals, unknown keys), `deploy/kockpit-backend`.
- **Tear down**: terminate the instance, release the Elastic IP, delete the security group and the DNS records.

## Limits

- One node, no high availability: an instance or AZ failure stops the service until the instance is back.
- The gateway runs one replica: its in-memory limits (unknown keys per IP, messages per key) are per replica.
  The daily audit quota is in PostgreSQL and holds whatever the number of replicas.
- A revoked key keeps working for up to 30 seconds (the gateway's key cache).
