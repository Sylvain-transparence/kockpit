terraform {
  required_version = ">= 1.4"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 4.59.0"
    }
  }
}

# Data sources for existing infrastructure
data "aws_vpc" "main" {
  id = var.vpc_id
}

data "aws_ecs_cluster" "main" {
  cluster_name = var.ecs_cluster_name
}

data "aws_caller_identity" "current" {}

module "ecs_app" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/ecs-app-module?ref=dev"

  application = var.service_name
  env         = var.kockpit_env
  stack       = "kockpit-audit-stream"
  region      = var.aws_region
  account_id  = data.aws_caller_identity.current.account_id
  tags        = var.tags

  aws_ecs_cluster_id   = data.aws_ecs_cluster.main.id
  aws_ecs_cluster_name = var.ecs_cluster_name
  vpc                  = data.aws_vpc.main
  subnets              = var.private_subnet_ids

  # No Cloud Map consumers today; audit-stream is only reached via the ALB.
  enable_service_discovery = false

  # Load balancer
  aws_lb_listener_arn    = var.lb_listener_arn
  lb_security_group_id   = var.lb_security_group_id
  listener_rule_priority = var.listener_rule_priority
  path_routing_patterns  = var.path_patterns
  http_method_conditions = var.http_methods

  # Task
  task_image_url = local.container_image
  task_cpu       = var.cpu
  task_memory    = var.memory

  cpu_architecture = var.cpu_architecture
  desired_count    = var.desired_count

  service_port             = var.container_port
  service_healthcheck_port = var.health_check_port

  service_healthcheck_path                = var.health_check_path
  service_healthcheck_timeout             = var.health_check_timeout
  service_healthcheck_interval            = var.health_check_interval
  service_healthcheck_healthy_threshold   = 3
  service_healthcheck_unhealthy_threshold = var.health_check_unhealthy_threshold

  environment_variables = local.environment_variables

  log_retention_in_days = var.log_retention_days
}

# IAM policy for Kinesis consumer access
resource "aws_iam_role_policy" "kinesis_consumer_policy" {
  name = "${var.service_name}-kinesis-consumer"
  role = module.ecs_app.ecs_task_role

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "kinesis:GetRecords",
          "kinesis:GetShardIterator",
          "kinesis:DescribeStream",
          "kinesis:DescribeStreamSummary",
          "kinesis:ListShards",
          "kinesis:ListStreams",
          "kinesis:SubscribeToShard"
        ]
        Resource = "arn:aws:kinesis:${var.aws_region}:${data.aws_caller_identity.current.account_id}:stream/${var.kinesis_stream_name}"
      }
    ]
  })
}

# IAM policy for DynamoDB access (KCL lease/checkpoint table)
resource "aws_iam_role_policy" "dynamodb_kcl_policy" {
  name = "${var.service_name}-dynamodb-kcl"
  role = module.ecs_app.ecs_task_role

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "dynamodb:CreateTable",
          "dynamodb:PutItem",
          "dynamodb:GetItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Scan",
          "dynamodb:Query",
          "dynamodb:DescribeTable",
          "dynamodb:DescribeTimeToLive",
          "dynamodb:UpdateTimeToLive",
          "dynamodb:TagResource",
          "dynamodb:DescribeContributorInsights",
          "dynamodb:UpdateContributorInsights",
          "dynamodb:DescribeContinuousBackups"
        ]
        Resource = "arn:aws:dynamodb:${var.aws_region}:${data.aws_caller_identity.current.account_id}:table/${var.kinesis_app_name}"
      }
    ]
  })
}

# IAM policy for writing the audit archive (opensearch-s3 consumer). The stream only uploads: each
# batch is one PutObject carrying its retention=<days> tag, which S3 also authorizes as
# PutObjectTagging. Reading archived audits back is the console backend's job, not this task's.
resource "aws_iam_role_policy" "s3_audit_archive_policy" {
  count = var.audit_archive_enabled ? 1 : 0
  name  = "${var.service_name}-s3-audit-archive"
  role  = module.ecs_app.ecs_task_role

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = "arn:aws:s3:::${coalesce(var.audit_bucket_name, "unset")}/*"
      }
    ]
  })

  lifecycle {
    precondition {
      condition     = var.audit_bucket_name != null
      error_message = "audit_archive_enabled needs audit_bucket_name: the bucket the stream archives to."
    }
  }
}

# IAM policy for OpenSearch access
resource "aws_iam_role_policy" "opensearch_policy" {
  name = "${var.service_name}-opensearch"
  role = module.ecs_app.ecs_task_role

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "es:ESHttpGet",
          "es:ESHttpPost",
          "es:ESHttpPut",
          "es:ESHttpDelete",
          "es:ESHttpHead",
          "es:DescribeElasticsearchDomains",
          "es:ListDomainNames"
        ]
        Resource = "*"
      }
    ]
  })
}
