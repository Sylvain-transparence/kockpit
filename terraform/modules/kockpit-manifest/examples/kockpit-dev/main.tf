module "manifest" {
  source = "../../"

  domain = "kockpit"
  env    = "dev"
  name   = "Kockpit samples"

  feature_flipping_services = [
    {
      name = "Sample Legacy"
      id   = "kockpit-samples"
      keys = [
        {
          key            = "ff.key.always-active"
          enabled        = true
          expirationDate = "2026-12-31"
          comment        = "Sample feature always enable"
        },
        {
          key     = "ff.key.flag"
          enabled = false
          comment = "Sample feature with false value"
        }
      ]
    }
  ]

  dyna_config_services = [
    {
      name = "Samples Legacy"
      id   = "kockpit-samples"
      keys = [
        {
          key         = "application.client.apim.referential.timeout"
          value       = 1000
          description = "legacy value"
        },
        {
          key         = "dyna.property.backend.call.timeout"
          value       = 2
          description = "legacy value for dyna.property.backend.call.timeout"
        },
        {
          key         = "dyna.property.long-valued"
          value       = "any long value to set manage via dyna config"
          description = "legacy value for dyna.property.long"
        }
      ]
    }
  ]

  audit_services = [
    {
      name    = "Samples Audits"
      id      = "samples"
      columns = ["appId", "requestId", "method", "path", "duration", "start", "status"]
      # only the audits of these applications (omit to show every application of the domain/env)
      app_ids = ["kockpit-app-1", "kockpit-app-2"]
      search_columns = [
        {
          name    = "httpStatus"
          type    = "options"
          options = [200, 201, 400, 404, 401, 500]
          path    = "indexedKeyValues.httpStatus"
        },
        {
          name    = "appId"
          type    = "options"
          options = ["kockpit-app-1", "kockpit-app-2"]
        },
        {
          name    = "httpMethod"
          type    = "options"
          options = ["GET", "POST", "PUT", "DELETE"]
          path    = "indexedKeyValues.httpMethod"
        }
      ]
    }
  ]
}

# ── Upload to S3 (AWS) ────────────────────────────────────────────────────────
# The bucket is expected to already exist (created by the parent project).

resource "aws_s3_object" "manifest" {
  bucket       = "my-manifests-bucket"
  key          = "kockpit-dev-manifest.json"
  content      = module.manifest.manifest_json
  content_type = "application/json"

  tags = {
    Domain      = "kockpit"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

# ── Upload to Azure Blob Storage ──────────────────────────────────────────────
# The storage account and container are expected to already exist. storage_container_id is the
# container's Azure resource ID (azurerm 5 dropped storage_account_name/storage_container_name).

resource "azurerm_storage_blob" "manifest" {
  name                 = "kockpit-dev-manifest.json"
  storage_container_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/my-resource-group/providers/Microsoft.Storage/storageAccounts/mystorageaccount/blobServices/default/containers/manifests"
  type                 = "Block"
  source_content       = module.manifest.manifest_json
  content_type         = "application/json"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "manifest_json" {
  value = module.manifest.manifest_json
}