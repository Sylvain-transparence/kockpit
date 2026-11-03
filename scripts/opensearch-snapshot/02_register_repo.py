#!/usr/bin/env python3
"""
Step 2 - register the S3 snapshot repository on the OpenSearch domain, then verify it.

Registering a repository on Amazon OpenSearch Service passes an IAM role to the service, so -
unlike every other snapshot call - the request MUST be SigV4-signed by an identity allowed to
iam:PassRole that role. This script signs it with your AWS CLI credentials (no extra packages).

It works through an SSM tunnel: it connects to --host (e.g. https://localhost:9200) but signs
for, and sends Host: <--endpoint-host>, which is what the domain checks the signature against.

  ./02_register_repo.py --host https://localhost:9200 --insecure --profile my-aws-profile \\
      --endpoint-host vpc-my-domain-xxxxxxxxxxxx.eu-west-1.es.amazonaws.com \\
      --bucket kockpit-os-snapshots-pro-123456789012 \\
      --role-arn arn:aws:iam::123456789012:role/kockpit-opensearch-snapshot-pro
"""
import argparse
import datetime
import hashlib
import hmac
import json
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request



def _hmac(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def sigv4_headers(method, host, path, query, body, region, service, creds, now=None,
                  sign_content_sha=True):
    """Returns the headers to send (Host included) for an AWS SigV4-signed request."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()

    headers = {"host": host, "x-amz-date": amz_date}
    if sign_content_sha:
        headers["x-amz-content-sha256"] = payload_hash
    if body:
        headers["content-type"] = "application/json"
    if creds.get("SessionToken"):
        headers["x-amz-security-token"] = creds["SessionToken"]

    canonical_query = "&".join(
        f"{urllib.parse.quote(k, safe='-_.~')}={urllib.parse.quote(v, safe='-_.~')}"
        for k, v in sorted(query.items()))
    signed = sorted(headers)
    canonical_request = "\n".join([
        method,
        urllib.parse.quote(path, safe="/-_.~"),
        canonical_query,
        "".join(f"{h}:{headers[h].strip()}\n" for h in signed),
        ";".join(signed),
        payload_hash,
    ])
    scope = f"{date}/{region}/{service}/aws4_request"
    string_to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope,
                                hashlib.sha256(canonical_request.encode()).hexdigest()])
    key = _hmac(_hmac(_hmac(_hmac(("AWS4" + creds["SecretAccessKey"]).encode(), date), region), service),
                "aws4_request")
    signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = (f"AWS4-HMAC-SHA256 Credential={creds['AccessKeyId']}/{scope}, "
                                f"SignedHeaders={';'.join(signed)}, Signature={signature}")
    return headers


def load_credentials(profile):
    cmd = ["aws", "configure", "export-credentials", "--format", "process"]
    if profile:
        cmd += ["--profile", profile]
    try:
        return json.loads(subprocess.run(cmd, check=True, capture_output=True, text=True).stdout)
    except subprocess.CalledProcessError as e:
        sys.exit(f"❌ Could not get AWS credentials ({' '.join(cmd)}): {e.stderr.strip()}")


def call(args, creds, method, path, body=None, query=None):
    data = json.dumps(body).encode() if body is not None else b""
    query = query or {}
    headers = sigv4_headers(method, args.endpoint_host, path, query, data, args.region, "es", creds)
    url = args.host.rstrip("/") + path + ("?" + urllib.parse.urlencode(query) if query else "")
    req = urllib.request.Request(url, data=data or None, method=method, headers=headers)
    ctx = ssl._create_unverified_context() if args.insecure else None
    try:
        with urllib.request.urlopen(req, timeout=120, context=ctx) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", required=True, help="Where to connect, e.g. https://localhost:9200 (tunnel)")
    p.add_argument("--endpoint-host", required=True,
                   help="The domain's real endpoint host name, used for signing, "
                        "e.g. vpc-my-domain-xxxxxxxxxxxx.eu-west-1.es.amazonaws.com")
    p.add_argument("--insecure", action="store_true", help="Skip TLS verification (needed through a tunnel)")
    p.add_argument("--profile", help="AWS CLI profile, e.g. my-aws-profile")
    p.add_argument("--region", default="eu-west-1")
    p.add_argument("--repository", default="orders-archive")
    p.add_argument("--bucket", required=True)
    p.add_argument("--base-path", default="orders-auditdata", help="Key prefix inside the bucket")
    p.add_argument("--role-arn", required=True)
    args = p.parse_args()

    creds = load_credentials(args.profile)
    print(f"➡️  Registering repository '{args.repository}' -> s3://{args.bucket}/{args.base_path}")
    status, body = call(args, creds, "PUT", f"/_snapshot/{args.repository}", {
        "type": "s3",
        "settings": {
            "bucket": args.bucket,
            "base_path": args.base_path,
            "region": args.region,
            "role_arn": args.role_arn,
            "server_side_encryption": True,
        },
    })
    print(f"   HTTP {status}: {body}")
    if status != 200:
        sys.exit("❌ Registration failed. 403 mentioning iam:PassRole = your identity may not pass "
                 "the role; 'The security token included in the request is invalid' = refresh "
                 "credentials; signature mismatch = check --endpoint-host.")

    # Makes every data node actually write/read/delete a test blob in the bucket - catches a wrong
    # bucket policy or role trust now, rather than at snapshot time.
    print(f"➡️  Verifying repository '{args.repository}' from all nodes")
    status, body = call(args, creds, "POST", f"/_snapshot/{args.repository}/_verify")
    print(f"   HTTP {status}: {json.dumps(body)[:1500] if isinstance(body, dict) else body[:1500]}")
    if status != 200:
        sys.exit("❌ Verification failed - check the role's S3 policy and trust relationship.")
    print(f"✅ Repository '{args.repository}' registered and verified on {len(body.get('nodes', {}))} nodes.")


if __name__ == "__main__":
    main()
