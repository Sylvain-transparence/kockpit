// Host-level monitoring of the OpenSearch VMs, optionally alerting to a Microsoft Teams channel.
//
//   VMs (Azure Monitor Agent, installed by the opensearch module)
//     -> DCR (Perf + Syslog) -> Log Analytics workspace
//     -> scheduled query alerts -> [action group -> Logic App -> Teams Workflows webhook]
//
// Teams delivery (teams_enabled) needs teams_webhook_url, the URL of a Teams Workflows flow "Post to a channel when
// a webhook request is received". Without it, collection and alert rules are still deployed: alerts fire in Azure
// Monitor, nothing is posted.
// Cluster-level checks (health, heap, rejections, shards...) are OpenSearch Alerting monitors, see
// opensearch-monitor/alerting in this repository.

locals {
  vm_ids      = [for id in var.vm_ids : lower(id)]
  teams_count = var.teams_enabled ? 1 : 0
}

// ---------------------------------------------------------------------------------------------------------------------
// Collection
// ---------------------------------------------------------------------------------------------------------------------

// Perf and Syslog go to Log Analytics only: Microsoft-Perf cannot be routed to azure_monitor_metrics,
// which is what made the previous (commented-out) DCR fail with 400 Bad Request.
resource "azurerm_monitor_data_collection_rule" "opensearch" {
  name                = "${var.name_prefix}-opensearch-dcr-${var.environment}"
  location            = var.location
  resource_group_name = var.rg_name
  kind                = "Linux"

  destinations {
    log_analytics {
      workspace_resource_id = var.log_analytics_workspace_id
      name                  = "law"
    }
  }

  data_flow {
    streams      = ["Microsoft-Perf", "Microsoft-Syslog"]
    destinations = ["law"]
  }

  data_sources {
    performance_counter {
      name                          = "opensearch-perf"
      streams                       = ["Microsoft-Perf"]
      sampling_frequency_in_seconds = 60
      counter_specifiers = [
        "Processor(*)\\% Processor Time",
        "Memory(*)\\% Available Memory",
        "Memory(*)\\Available MBytes Memory",
        "Logical Disk(*)\\% Used Space",
        "Logical Disk(*)\\Free Megabytes",
      ]
    }

    syslog {
      name           = "opensearch-syslog"
      streams        = ["Microsoft-Syslog"]
      facility_names = ["kern", "daemon", "syslog"]
      log_levels     = ["Warning", "Error", "Critical", "Alert", "Emergency"]
    }
  }

  tags = {
    environment = var.environment
    project     = var.name_prefix
  }
}

resource "azurerm_monitor_data_collection_rule_association" "opensearch" {
  count                   = length(var.vm_ids)
  name                    = "${var.name_prefix}-opensearch-dcra-${count.index}-${var.environment}"
  target_resource_id      = var.vm_ids[count.index]
  data_collection_rule_id = azurerm_monitor_data_collection_rule.opensearch.id
}

// ---------------------------------------------------------------------------------------------------------------------
// Delivery to Teams
// ---------------------------------------------------------------------------------------------------------------------

// Teams Workflows webhooks expect an Adaptive Card, Azure Monitor sends the common alert schema:
// this Logic App converts one into the other.
resource "azurerm_logic_app_workflow" "alerts-to-teams" {
  count               = local.teams_count
  name                = "${var.name_prefix}-alerts-to-teams-${var.environment}"
  location            = var.location
  resource_group_name = var.rg_name

  tags = {
    environment = var.environment
    project     = var.name_prefix
  }
}

resource "azurerm_logic_app_trigger_http_request" "alert" {
  count        = local.teams_count
  name         = "azure-monitor-alert"
  logic_app_id = azurerm_logic_app_workflow.alerts-to-teams[0].id
  schema       = jsonencode({})
}

resource "azurerm_logic_app_action_http" "post-to-teams" {
  count        = local.teams_count
  name         = "post-to-teams"
  logic_app_id = azurerm_logic_app_workflow.alerts-to-teams[0].id
  method       = "POST"
  uri          = var.teams_webhook_url
  headers      = { "Content-Type" = "application/json" }

  lifecycle {
    precondition {
      condition     = var.teams_webhook_url != null
      error_message = "teams_enabled needs teams_webhook_url."
    }
  }

  // State is spelled out (FIRED / RESOLVED) with distinct icons: never rely on color alone.
  body = jsonencode({
    type = "message"
    attachments = [{
      contentType = "application/vnd.microsoft.card.adaptive"
      content = {
        "$schema" = "http://adaptivecards.io/schemas/adaptive-card.json"
        type      = "AdaptiveCard"
        version   = "1.4"
        msteams   = { width = "Full" }
        body = [
          {
            type   = "TextBlock"
            size   = "Large"
            weight = "Bolder"
            wrap   = true
            text   = "@{if(equals(triggerBody()?['data']?['essentials']?['monitorCondition'], 'Fired'), '🔥 FIRED', '✅ RESOLVED')} · ${upper(var.environment)} · @{triggerBody()?['data']?['essentials']?['alertRule']}"
          },
          {
            type = "TextBlock"
            wrap = true
            text = "@{triggerBody()?['data']?['essentials']?['description']}"
          },
          {
            type = "FactSet"
            facts = [
              { title = "Severity", value = "@{triggerBody()?['data']?['essentials']?['severity']} (Sev0 = critical)" },
              { title = "Since", value = "@{triggerBody()?['data']?['essentials']?['firedDateTime']}" },
              { title = "Resolved", value = "@{coalesce(triggerBody()?['data']?['essentials']?['resolvedDateTime'], '-')}" },
              { title = "Dimensions", value = "@{string(triggerBody()?['data']?['alertContext']?['condition']?['allOf']?[0]?['dimensions'])}" },
              { title = "Value", value = "@{triggerBody()?['data']?['alertContext']?['condition']?['allOf']?[0]?['metricValue']}" },
            ]
          },
        ]
      }
    }]
  })
}

resource "azurerm_monitor_action_group" "ops" {
  count               = local.teams_count
  name                = "${var.name_prefix}-ops-${var.environment}"
  resource_group_name = var.rg_name
  short_name          = substr("${var.name_prefix}-${var.environment}", 0, 12)

  webhook_receiver {
    name                    = "teams"
    service_uri             = azurerm_logic_app_trigger_http_request.alert[0].callback_url
    use_common_alert_schema = true
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Alert rules
// ---------------------------------------------------------------------------------------------------------------------

locals {
  disk_query = <<-KQL
    Perf
    | where ObjectName == "Logical Disk" and CounterName == "% Used Space"
    | where InstanceName in (${join(", ", [for p in var.disk_mount_points : jsonencode(p)])})
    | summarize UsedPercent = max(CounterValue) by Computer, InstanceName
  KQL

  // OpenSearch default disk watermarks: low 85% (no new shards), high 90% (shards moved away), flood 96% (read-only).
  disk_thresholds = {
    warning  = { severity = 2, threshold = 80 }
    critical = { severity = 1, threshold = 88 }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "disk" {
  for_each             = local.disk_thresholds
  name                 = "${var.name_prefix}-opensearch-disk-${each.key}-${var.environment}"
  display_name         = "OpenSearch disk usage ${each.key} (> ${each.value.threshold}%)"
  description          = "A disk of an OpenSearch node is over ${each.value.threshold}% used. Above 90% OpenSearch moves shards away, at 95-96% it makes indices read-only."
  location             = var.location
  resource_group_name  = var.rg_name
  scopes               = [var.log_analytics_workspace_id]
  severity             = each.value.severity
  evaluation_frequency = "PT5M"
  window_duration      = "PT10M"

  criteria {
    query                   = local.disk_query
    time_aggregation_method = "Maximum"
    metric_measure_column   = "UsedPercent"
    operator                = "GreaterThan"
    threshold               = each.value.threshold

    dimension {
      name     = "Computer"
      operator = "Include"
      values   = ["*"]
    }
    dimension {
      name     = "InstanceName"
      operator = "Include"
      values   = ["*"]
    }

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = true
  dynamic "action" {
    for_each = azurerm_monitor_action_group.ops
    content {
      action_groups = [action.value.id]
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "memory" {
  name                 = "${var.name_prefix}-opensearch-memory-${var.environment}"
  display_name         = "OpenSearch node low on memory (< ${var.memory_alert_threshold_percent}% available)"
  description          = "Available memory (page cache included) on an OpenSearch node has been under ${var.memory_alert_threshold_percent}% for 15 minutes: risk of OOM kill."
  location             = var.location
  resource_group_name  = var.rg_name
  scopes               = [var.log_analytics_workspace_id]
  severity             = 2
  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"

  criteria {
    query                   = <<-KQL
      Perf
      | where ObjectName == "Memory" and CounterName == "% Available Memory"
      | summarize AvailablePercent = max(CounterValue) by Computer
    KQL
    time_aggregation_method = "Maximum"
    metric_measure_column   = "AvailablePercent"
    operator                = "LessThan"
    threshold               = var.memory_alert_threshold_percent

    dimension {
      name     = "Computer"
      operator = "Include"
      values   = ["*"]
    }

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = true
  dynamic "action" {
    for_each = azurerm_monitor_action_group.ops
    content {
      action_groups = [action.value.id]
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "heartbeat" {
  name                 = "${var.name_prefix}-opensearch-heartbeat-${var.environment}"
  display_name         = "OpenSearch VM not reporting"
  description          = "At least one OpenSearch VM has sent no heartbeat for 5 minutes: VM down, agent down or network issue."
  location             = var.location
  resource_group_name  = var.rg_name
  scopes               = [var.log_analytics_workspace_id]
  severity             = 1
  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"

  // The expected VMs are listed explicitly: a VM that stopped reporting has no rows left to alert on.
  criteria {
    query                   = <<-KQL
      let expected = dynamic(${jsonencode(local.vm_ids)});
      let alive = Heartbeat
        | where TimeGenerated > ago(5m)
        | distinct VmId = tolower(_ResourceId);
      print VmId = expected
      | mv-expand VmId to typeof(string)
      | join kind=leftanti alive on VmId
      | extend Vm = tostring(split(VmId, "/")[-1])
      | summarize Missing = count() by Vm
    KQL
    time_aggregation_method = "Maximum"
    metric_measure_column   = "Missing"
    operator                = "GreaterThan"
    threshold               = 0

    dimension {
      name     = "Vm"
      operator = "Include"
      values   = ["*"]
    }

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = true
  dynamic "action" {
    for_each = azurerm_monitor_action_group.ops
    content {
      action_groups = [action.value.id]
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "oom-kill" {
  name                = "${var.name_prefix}-opensearch-oom-kill-${var.environment}"
  display_name        = "OpenSearch node: process killed by the kernel (OOM)"
  description         = "The Linux OOM killer killed a process on an OpenSearch node (most likely the OpenSearch JVM)."
  location            = var.location
  resource_group_name = var.rg_name
  scopes              = [var.log_analytics_workspace_id]
  severity            = 0
  // The Syslog table only appears once the DCR above has sent data: without this the first apply fails.
  skip_query_validation = true
  evaluation_frequency  = "PT5M"
  window_duration       = "PT5M"

  criteria {
    query                   = <<-KQL
      Syslog
      | where Facility == "kern"
      | where SyslogMessage has "Out of memory" or SyslogMessage has "oom-kill"
      | summarize Events = count() by Computer
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Events"
    operator                = "GreaterThan"
    threshold               = 0

    dimension {
      name     = "Computer"
      operator = "Include"
      values   = ["*"]
    }

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = true
  dynamic "action" {
    for_each = azurerm_monitor_action_group.ops
    content {
      action_groups = [action.value.id]
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "opensearch-service" {
  name                = "${var.name_prefix}-opensearch-service-failed-${var.environment}"
  display_name        = "OpenSearch service failed"
  description         = "systemd reports opensearch.service as failed on a node (crash, kill or failed start)."
  location            = var.location
  resource_group_name = var.rg_name
  scopes              = [var.log_analytics_workspace_id]
  severity            = 1
  // The Syslog table only appears once the DCR above has sent data: without this the first apply fails.
  skip_query_validation = true
  evaluation_frequency  = "PT5M"
  window_duration       = "PT5M"

  criteria {
    query                   = <<-KQL
      Syslog
      | where SyslogMessage has "opensearch.service"
      | where SyslogMessage has "Failed with result" or SyslogMessage has "Main process exited"
      | summarize Events = count() by Computer
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Events"
    operator                = "GreaterThan"
    threshold               = 0

    dimension {
      name     = "Computer"
      operator = "Include"
      values   = ["*"]
    }

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = true
  dynamic "action" {
    for_each = azurerm_monitor_action_group.ops
    content {
      action_groups = [action.value.id]
    }
  }
}
