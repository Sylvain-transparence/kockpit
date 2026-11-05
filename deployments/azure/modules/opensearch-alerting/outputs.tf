output "data_collection_rule_id" {
  description = "ID of the DCR sending the OpenSearch VMs' Perf and Syslog to the workspace"
  value       = azurerm_monitor_data_collection_rule.opensearch.id
}

output "action_group_id" {
  description = "ID of the action group posting to Teams, null when teams_enabled is false"
  value       = one(azurerm_monitor_action_group.ops[*].id)
}
