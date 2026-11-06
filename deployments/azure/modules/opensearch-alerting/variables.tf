variable "environment" {
  description = "Environment name, used in resource names and shown in the Teams cards (dev, pro...)"
  type        = string
}

variable "location" {
  type = string
}

variable "rg_name" {
  description = "Resource group of the alerting resources"
  type        = string
}

variable "log_analytics_workspace_id" {
  description = "Log Analytics workspace receiving the VMs' Perf and Syslog, and queried by the alert rules"
  type        = string
}

variable "vm_ids" {
  description = "IDs of the OpenSearch VMs (with the Azure Monitor Agent installed), e.g. module.opensearch[*].vm_id"
  type        = list(string)
}

variable "disk_mount_points" {
  description = "Mount points watched by the disk usage alerts"
  type        = list(string)
  default     = ["/", "/data/opensearch"]
}

variable "memory_alert_threshold_percent" {
  description = "The memory alert fires when a node stays under this % of available memory. Keep it below the nodes' normal level, or the alert flaps."
  type        = number
  default     = 10
}

variable "teams_enabled" {
  description = "Post the alerts to Teams. A separate flag, as Terraform cannot count on the (sensitive) URL."
  type        = bool
  default     = false
}

variable "teams_webhook_url" {
  description = "Teams Workflows webhook URL (flow \"Post to a channel when a webhook request is received\"); required when teams_enabled"
  type        = string
  default     = null
  sensitive   = true
}

variable "name_prefix" {
  description = "Prefix of the resource names"
  type        = string
  default     = "kockpit"
}
