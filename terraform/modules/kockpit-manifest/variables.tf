variable "domain" {
  description = "Domain identifier (e.g. platform)"
  type        = string
}

variable "env" {
  description = "Environment (e.g. dev, staging, production)"
  type        = string
}

variable "name" {
  description = "Human-readable name for this manifest"
  type        = string
}

variable "output_path" {
  description = "Local path where the manifest JSON file will be written"
  type        = string
  default     = null
}


# ── Feature Flipping ─────────────────────────────────────────────────────────

variable "feature_flipping_services" {
  description = "List of feature-flipping service definitions"
  type = list(object({
    name = string
    id   = string
    keys = list(object({
      key            = string
      enabled        = bool
      expirationDate = optional(string)
      comment        = optional(string)
    }))
  }))
  default = []
}

# ── Dyna Config ───────────────────────────────────────────────────────────────

variable "dyna_config_services" {
  description = "List of dyna-config service definitions"
  type = list(object({
    name = string
    id   = string
    keys = list(object({
      key         = string
      value       = any
      description = optional(string)
    }))
  }))
  default = []
}

# ── Audit ─────────────────────────────────────────────────────────────────────

variable "audit_services" {
  description = "List of audit service definitions"
  type = list(object({
    name    = string
    id      = string
    columns = list(string)
    # appIds whose audits this service shows; empty = every application of the domain/env
    app_ids = optional(list(string), [])
    search_columns = list(object({
      name    = string
      type    = string
      path    = optional(string)
      options = optional(list(any))
    }))
    saved_filters = optional(list(object({
      name = string
      filters = list(object({
        name   = string
        path   = string
        values = any
      }))
    })), [])
  }))
  default = []
}