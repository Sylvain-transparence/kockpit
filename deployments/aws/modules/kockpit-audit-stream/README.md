# kockpit-audit-stream

Terraform module for deploying the Kockpit Audit Stream application on AWS ECS Fargate.

The service consumes audit events from a Kinesis stream and indexes them into OpenSearch. It is exposed via an existing ALB listener using path-based routing.

This module is a thin wrapper around [`ecs-app-module`](../ecs-app-module), which provides the ECS
task/service, ALB target group/listener rule, security group, IAM roles, and CloudWatch log group.
This module adds the audit-stream-specific IAM policies (Kinesis, DynamoDB, OpenSearch) on top and
maps its own (audit-stream-flavored) variable names onto `ecs-app-module`'s inputs.

## Resources created

- Everything `ecs-app-module` creates: ECS task definition and service (Fargate), ALB target group and
  listener rule, security group, task + task execution IAM roles, CloudWatch log group. Service
  discovery is disabled (`enable_service_discovery = false`) since nothing consumes it today.
- Additional IAM policies on the task role for:
  - Kinesis consumer (`GetRecords`, `GetShardIterator`, `DescribeStream`, `ListShards`, ...)
  - DynamoDB for KCL lease/checkpoint management
  - OpenSearch indexing
- When `audit_bucket_name` is set, the expiry rules of the audit archive bucket (see
  [Audit archive retention](#audit-archive-retention)).

## Example usage

```hcl
locals {
  kockpit_audit_stream_counts_per_env = {
    dev = 1
    oat = 0
    pro = 0
  }
  kockpit_audit_stream_count = lookup(local.kockpit_audit_stream_counts_per_env, local.environment)
}

module "kockpit-auditstream" {
  source = "../../modules/kockpit-audit-stream"
  count  = local.kockpit_audit_stream_count

  # Naming — service_name is the base name only; kockpit_env supplies the
  # "-${env}" suffix (via ecs-app-module's application/env split), so don't
  # bake the environment into service_name here.
  service_name = "kockpit-auditstream"

  # AWS
  aws_region = var.aws_region

  # Network
  vpc_id             = data.aws_vpc.this.id
  private_subnet_ids = data.aws_subnets.privates.ids
  ecs_cluster_name   = aws_ecs_cluster.this.name

  # Load balancer
  lb_listener_arn        = data.aws_alb_listener.platform-alb-https.arn
  lb_security_group_id   = data.aws_security_group.platform-lb_allow_http.id
  listener_rule_priority = 200
  path_patterns          = ["/auditservice/v2/*"]
  http_methods           = ["POST"]

  # Application
  kockpit_env          = local.environment
  opensearch_endpoints = data.aws_opensearch_domain.os.endpoint

  # Kinesis
  kinesis_stream_name = "auditstream-${local.environment}"
  kinesis_app_name    = "kockpit-auditstream-${local.environment}"

  # Sizing
  cpu           = 512
  memory        = 1024
  desired_count = 1

  # Logging
  log_retention_days = local.environment == "pro" ? 7 : 30

  tags = {
    Environment = local.environment
    Project     = "kockpit"
    ManagedBy   = "terraform"
  }
}

# Attach additional app-role policy (e.g. kockpit-sdk-app-role)
resource "aws_iam_role_policy" "auditstream_app_role" {
  count  = local.kockpit_audit_stream_count
  name   = "kockpit-auditstream-ownrole"
  role   = module.kockpit-auditstream[0].task_role_name
  policy = data.aws_iam_policy_document.ecs_auditstream_app_access.json
}
```

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|----------|
| `aws_region` | AWS region | `string` | `eu-west-1` | no |
| `service_name` | ECS service name | `string` | `kockpit-auditstream` | no |
| `vpc_id` | VPC ID | `string` | — | yes |
| `private_subnet_ids` | Private subnet IDs for ECS tasks | `list(string)` | — | yes |
| `ecs_cluster_name` | Name of the existing ECS cluster | `string` | — | yes |
| `lb_listener_arn` | ARN of the existing ALB listener | `string` | — | yes |
| `lb_security_group_id` | Security group ID of the load balancer | `string` | — | yes |
| `listener_rule_priority` | Priority for the ALB listener rule | `number` | `200` | no |
| `path_patterns` | Path patterns for the ALB listener rule | `list(string)` | `["/auditservice/v2/*"]` | no |
| `http_methods` | HTTP method conditions for the ALB rule | `list(string)` | `["POST"]` | no |
| `image_tag` | Docker image tag | `string` | `latest` | no |
| `container_port` | Container port | `number` | `8080` | no |
| `health_check_port` | Health check port | `string` | `8090` | no |
| `health_check_path` | Health check path | `string` | `/actuator/health` | no |
| `health_check_timeout` | Health check timeout (seconds) | `number` | `20` | no |
| `health_check_interval` | Health check interval (seconds) | `number` | `25` | no |
| `health_check_unhealthy_threshold` | Consecutive failures before unhealthy | `number` | `10` | no |
| `cpu_architecture` | `ARM64` or `X86_64` | `string` | `X86_64` | no |
| `cpu` | CPU units (1024 = 1 vCPU) | `number` | `512` | no |
| `memory` | Memory in MB | `number` | `1024` | no |
| `desired_count` | Desired task count | `number` | `1` | no |
| `log_retention_days` | CloudWatch log retention in days | `number` | `30` | no |
| `kockpit_env` | Kockpit environment (`dev`, `oat`, `pro`) | `string` | — | yes |
| `opensearch_endpoints` | OpenSearch cluster endpoint | `string` | — | yes |
| `kinesis_stream_name` | Kinesis stream name to consume from | `string` | — | yes |
| `kinesis_app_name` | KCL app name (DynamoDB checkpoint table) | `string` | — | yes |
| `consumer_poll_max_records` | Max records per Kinesis poll | `number` | `500` | no |
| `consumer_initial_position_in_stream` | `TRIM_HORIZON` or `LATEST` | `string` | `TRIM_HORIZON` | no |
| `elasticsearch_indexation_partition_size` | OpenSearch indexation batch size | `number` | `50` | no |
| `audit_trace_enabled` | Enable audit trace logging | `bool` | `false` | no |
| `audit_bucket_name` | Existing audit archive bucket to put the expiry rules on; `null` to skip | `string` | `null` | no |
| `audit_retention_days` | Allowed `retention` tag values, one expiry rule each; also passed as `allowed_ttl_days` | `list(number)` | `[1, 7, 14, 30, 60, 90, 120]` | no |
| `additional_environment_variables` | Extra environment variables | `list(object({name, value}))` | `[]` | no |
| `tags` | Tags applied to all resources | `map(string)` | — | no |

## Audit archive retention

With `kockpit.audit.stream.consumer=opensearch-s3`, the audit stream archives batches of audits to
S3 and tags each object `retention=<days>`: the longest TTL of the audits in it, rounded up to one
of `audit_retention_days` (capped at the longest). The tag does nothing by itself; this module
turns it into expiry by putting one lifecycle rule per value on `audit_bucket_name`:

| Rule | Filter | Expires after |
|------|--------|---------------|
| `audit-retention-7d` | tag `retention=7` | 7 days (noncurrent versions: 1 more day) |

- The module passes `audit_retention_days` to the application as `KOCKPIT_AUDIT_STREAM_S3_ALLOWED_TTL_DAYS`,
  so the stream never writes a tag with no rule. Change the list here, not in the application.
- `aws_s3_bucket_lifecycle_configuration` replaces every lifecycle rule on the bucket: use a bucket
  dedicated to audits.
- Objects with no `retention` tag (written before the stream tagged them, or by a producer that
  offloads its own reports) match no rule and are kept.
- The bucket itself, write access to it (`s3:PutObject`, `s3:PutObjectTagging`) and the
  `opensearch-s3` consumer mode are not set by this module.

## Outputs

| Name | Description |
|------|-------------|
| `ecs_service_name` | Name of the ECS service |
| `ecs_service_arn` | ARN of the ECS service |
| `task_definition_arn` | ARN of the task definition |
| `target_group_arn` | ARN of the ALB target group |
| `security_group_id` | ID of the ECS service security group |
| `task_execution_role_arn` | ARN of the task execution role |
| `task_role_arn` | ARN of the task role |
| `task_role_name` | Name of the task role (for attaching additional policies) |
| `log_group_name` | Name of the CloudWatch log group |
