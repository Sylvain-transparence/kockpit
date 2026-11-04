# Expiry of the audit archive (kockpit.audit.stream.consumer=opensearch-s3).
#
# The audit stream tags every batch object it writes with retention=<days>, rounded up to one of
# kockpit.audit.stream.s3.allowed_ttl_days. S3 itself does nothing with that tag: these rules,
# one per allowed value, are what expire the objects. A tag value with no rule is never deleted,
# which is why the same list is also passed to the application (see variables.tf).
#
# aws_s3_bucket_lifecycle_configuration owns ALL the bucket's lifecycle rules: any rule set on the
# bucket outside this resource is removed on apply. Point it at a bucket used only for audits.
resource "aws_s3_bucket_lifecycle_configuration" "audit_retention" {
  count  = var.audit_bucket_name == null ? 0 : 1
  bucket = var.audit_bucket_name

  dynamic "rule" {
    for_each = toset(var.audit_retention_days)
    content {
      id     = "audit-retention-${rule.value}d"
      status = "Enabled"

      filter {
        tag {
          key   = "retention"
          value = tostring(rule.value)
        }
      }

      expiration {
        days = rule.value
      }

      # Only matters if the bucket is versioned: expiring the current version leaves it behind as
      # a noncurrent one, still billed and still readable.
      noncurrent_version_expiration {
        noncurrent_days = 1
      }
    }
  }
}
