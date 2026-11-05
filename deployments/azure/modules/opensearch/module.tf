variable "environment" { type = string }
variable "nodes_count" { type = number }
variable "node_index" { type = number }
variable "username" { type = string }
variable "password" { type = string }
variable "location" { type = string }
variable "rg_name" { type = string }
variable "seeds" { type = string }
variable "subnet_id" { type = string }
variable "sg_id" { type = string }
variable "dns_name" { type = string }
variable "monitoring_ws_id" { type = string }
variable "os_disk_size" {
  type    = number
  default = 32
}
variable "disk_size" {
  type    = number
  default = 256
}
variable "vm_size" {
  type    = string
  default = "Standard_F2"
}
variable "heap_size" {
  description = "OpenSearch JVM heap (-Xms/-Xmx), about 50% of the VM's RAM: 2g on Standard_F2 (4 GB), 4g on Standard_B2ms (8 GB)"
  type        = string
  default     = "2g"
  validation {
    condition     = can(regex("^[0-9]+[mg]$", var.heap_size))
    error_message = "heap_size must look like 2g or 2048m."
  }
}
variable "data_path" {
  description = "Mount point of the data disk. Never under /mnt: on Azure that is the temporary disk, wiped on resize or deallocation."
  type        = string
  default     = "/data/opensearch"
  validation {
    condition     = !startswith(var.data_path, "/mnt")
    error_message = "data_path must not be under /mnt (Azure temporary disk)."
  }
}

resource "azurerm_network_interface" "ni" {
  name                = "kockpit-ni-${var.node_index}-${var.environment}"
  location            = var.location
  resource_group_name = var.rg_name
  ip_configuration {
    name                          = "config-${var.node_index}-${var.environment}"
    subnet_id                     = var.subnet_id
    private_ip_address_allocation = "Dynamic"
  }
}

resource "azurerm_network_interface_security_group_association" "sg-association" {
  network_interface_id      = azurerm_network_interface.ni.id
  network_security_group_id = var.sg_id
}

resource "azurerm_linux_virtual_machine" "opensearch-node" {
  name                            = "opensearch-${var.node_index}-${var.environment}"
  location                        = var.location
  admin_username                  = var.username
  admin_password                  = var.password
  disable_password_authentication = false
  network_interface_ids = [
    azurerm_network_interface.ni.id
  ]
  resource_group_name = var.rg_name
  size                = var.vm_size
  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
    disk_size_gb         = var.os_disk_size
  }
  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts"
    version   = "latest"
  }
  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_private_dns_a_record" "opensearch-node-dns" {
  name                = "opensearch-node-${var.node_index}"
  resource_group_name = var.rg_name
  ttl                 = 3600
  zone_name           = var.dns_name

  records = [azurerm_linux_virtual_machine.opensearch-node.private_ip_address]
}

resource "azurerm_virtual_machine_extension" "opensearch-install" {
  name                 = "opensearch-install"
  publisher            = "Microsoft.Azure.Extensions"
  type                 = "CustomScript"
  type_handler_version = "2.0"
  virtual_machine_id   = azurerm_linux_virtual_machine.opensearch-node.id
  protected_settings = <<PROT
  {
    "script": "${base64encode(templatefile("${path.module}/opensearch-install.sh", {
  env         = var.environment,
  index       = var.node_index,
  nodes_count = var.nodes_count,
  user        = var.username
  seeds       = var.seeds
  heap_size   = var.heap_size
  data_path   = var.data_path
}))}"
  }
PROT

# The script only provisions new VMs. Re-running it on a live node (which Azure does whenever the
# script changes) would rewrite the config of every node at once; existing nodes are changed one at
# a time with scripts/migrate-node.sh instead.
lifecycle {
  ignore_changes = [protected_settings]
}

tags = {
  env  = var.environment
  node = "opensearch-node${var.node_index}-${var.environment}"
}

depends_on = [azurerm_virtual_machine_data_disk_attachment.attachment256]
}

resource "azurerm_virtual_machine_extension" "opensearch-vm-extension" {
  name                       = "kockpit-vm-extension-${var.node_index}-${var.environment}"
  virtual_machine_id         = azurerm_linux_virtual_machine.opensearch-node.id
  publisher                  = "Microsoft.Azure.Monitor"
  type                       = "AzureMonitorLinuxAgent"
  type_handler_version       = "1.12"
  auto_upgrade_minor_version = true
}

resource "azurerm_role_assignment" "vm_monitor_role" {
  scope                = var.monitoring_ws_id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = azurerm_linux_virtual_machine.opensearch-node.identity[0].principal_id
}

resource "azurerm_monitor_data_collection_endpoint" "dce" {
  name                = "kockpit-dce-${var.node_index}-${var.environment}"
  location            = var.location
  resource_group_name = var.rg_name
  kind                = "Linux"
}

resource "azurerm_managed_disk" "data_disk_256" {
  name                 = "opensearech-${var.environment}-${var.node_index}-data"
  location             = var.location
  resource_group_name  = var.rg_name
  storage_account_type = "Standard_LRS"
  create_option        = "Empty"
  disk_size_gb         = var.disk_size
}

resource "azurerm_virtual_machine_data_disk_attachment" "attachment256" {
  managed_disk_id    = azurerm_managed_disk.data_disk_256.id
  virtual_machine_id = azurerm_linux_virtual_machine.opensearch-node.id
  lun                = 11
  caching            = "ReadWrite"
}

# Create Data Collection Rule (DCR) for metrics
/*
 fixme got 400 BAD REQUEST
resource "azurerm_monitor_data_collection_rule" "dcr" {
  name                        = "kockpit-dcr-${var.node_index}-${var.environment}"
  location                    = var.location
  resource_group_name         = var.rg_name
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.dce.id

  destinations {
    azure_monitor_metrics {
      name               = "destination-metrics-${var.node_index}"
    }
  }

  data_flow {
    streams = [
      "Microsoft-Perf",
      "Microsoft-Syslog"
    ]
    destinations = [
      "destination-metrics-${var.node_index}"
    ]
  }

  data_sources {
    performance_counter {
      streams                       = ["Microsoft-Perf"]
      sampling_frequency_in_seconds = 60
      counter_specifiers = [
        "Processor(*)\\% Processor Time"
      ]
      name = "perfCounterDataSource"
    }
  }
}
 */

/*
resource "azurerm_monitor_data_collection_rule_association" "dcra" {
  name                    = "kockpit-dcra-${var.node_index}-${var.environment}"
  target_resource_id      = azurerm_linux_virtual_machine.opensearch-node.id
  data_collection_rule_id = var.dcr_id
  description             = "Associate DCR to VM for metrics collection"
}
 */

/*
resource "azurerm_role_assignment" "vm_log_analytics_role" {
  scope              = azurerm_log_analytics_workspace.law.id
  role_definition_name = "Log Analytics Contributor"
  principal_id       = azurerm_linux_virtual_machine.vm.identity[0].principal_id
}
 */
