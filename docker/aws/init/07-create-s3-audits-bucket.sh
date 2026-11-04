#!/bin/bash
# Audit archive for the audit stream's local profile (kockpit.audit.stream.consumer=opensearch-s3,
# bucket audits-local), with the same expiry rules as deployments/aws/modules/kockpit-audit-stream:
# one per retention=<days> tag the stream can write (kockpit.audit.stream.s3.allowed_ttl_days).
export AWS_DEFAULT_REGION=eu-west-1

BUCKET=audits-local
RETENTION_DAYS="1 7 14 30 60 90 120"

rules=""
for days in $RETENTION_DAYS; do
  rules="${rules:+$rules,}{\"ID\":\"audit-retention-${days}d\",\"Status\":\"Enabled\",\"Filter\":{\"Tag\":{\"Key\":\"retention\",\"Value\":\"${days}\"}},\"Expiration\":{\"Days\":${days}},\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":1}}"
done

awslocal s3 mb s3://$BUCKET
awslocal s3api put-bucket-lifecycle-configuration --bucket $BUCKET --lifecycle-configuration "{\"Rules\":[$rules]}"
