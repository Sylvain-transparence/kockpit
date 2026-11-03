#!/usr/bin/env bash
# Step 1 - AWS side: an S3 bucket for manual OpenSearch snapshots, and the IAM role the
# OpenSearch service assumes to write into it. Control-plane only, runs from anywhere.
#
#   AWS_PROFILE=my-aws-profile ACCOUNT_ID=123456789012 DOMAIN_NAME=my-opensearch-domain ./01_create_bucket_and_role.sh
#
# Idempotent: existing bucket/role are kept, the role's policies are (re)applied.
set -euo pipefail

ACCOUNT_ID="${ACCOUNT_ID:?set ACCOUNT_ID to the AWS account id}"
REGION="${REGION:-eu-west-1}"
DOMAIN_NAME="${DOMAIN_NAME:?set DOMAIN_NAME to the OpenSearch domain name}"
BUCKET="${BUCKET:-kockpit-os-snapshots-pro-${ACCOUNT_ID}}"
ROLE_NAME="${ROLE_NAME:-kockpit-opensearch-snapshot-pro}"

DOMAIN_ARN="arn:aws:es:${REGION}:${ACCOUNT_ID}:domain/${DOMAIN_NAME}"

actual_account=$(aws sts get-caller-identity --query Account --output text)
if [[ "$actual_account" != "$ACCOUNT_ID" ]]; then
  echo "❌ Current credentials are for account $actual_account, expected $ACCOUNT_ID (set AWS_PROFILE)" >&2
  exit 1
fi

echo "➡️  Bucket s3://${BUCKET}"
if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "   already exists"
else
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  echo "   created"
fi
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

echo "➡️  Role ${ROLE_NAME}"
# Only the OpenSearch service, acting for this domain, may assume it.
trust_policy=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "es.amazonaws.com" },
    "Action": "sts:AssumeRole",
    "Condition": {
      "StringEquals": { "aws:SourceAccount": "${ACCOUNT_ID}" },
      "ArnLike": { "aws:SourceArn": "${DOMAIN_ARN}" }
    }
  }]
}
EOF
)
s3_policy=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:ListBucket"], "Resource": ["arn:aws:s3:::${BUCKET}"] },
    { "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::${BUCKET}/*"] }
  ]
}
EOF
)
if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE_NAME" --policy-document "$trust_policy"
  echo "   already exists, trust policy updated"
else
  aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$trust_policy" \
    --description "OpenSearch ${DOMAIN_NAME} manual snapshots to s3://${BUCKET}" >/dev/null
  echo "   created"
fi
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name s3-snapshot-access --policy-document "$s3_policy"

ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" --query Role.Arn --output text)
echo
echo "✅ Done. For step 2:"
echo "   BUCKET=${BUCKET}"
echo "   ROLE_ARN=${ROLE_ARN}"
echo
echo "Note: whoever runs step 2 needs iam:PassRole on ${ROLE_ARN} (and es:ESHttpPut on the domain)."
