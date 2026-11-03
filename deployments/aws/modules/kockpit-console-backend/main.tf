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

resource "aws_s3_bucket" "kockpit_data" {
  bucket = var.kockpit_data_s3_bucket
  tags   = var.tags
}

resource "aws_s3_bucket" "kockpit_manifests" {
  bucket = var.kockpit_manifests_s3_bucket
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "kockpit_data" {
  bucket                  = aws_s3_bucket.kockpit_data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "kockpit_manifests" {
  bucket                  = aws_s3_bucket.kockpit_manifests.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

module "ecs_app" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/ecs-app-module?ref=dev"

  application = var.service_name
  env         = var.kockpit_env
  stack       = "kockpit-console-backend"
  region      = var.aws_region
  account_id  = data.aws_caller_identity.current.account_id
  tags        = var.tags

  aws_ecs_cluster_id   = data.aws_ecs_cluster.main.id
  aws_ecs_cluster_name = var.ecs_cluster_name
  vpc                  = data.aws_vpc.main
  subnets              = var.private_subnet_ids

  # No Cloud Map consumers today; the service is only reached via the ALB.
  enable_service_discovery = false

  # This module owns its own dedicated ALB listener (below) rather than a
  # path-based rule on an existing shared listener, so let ecs-app-module
  # manage only the target group and security-group wiring, not a listener
  # rule of its own.
  enable_load_balancer = true
  create_listener_rule = false
  lb_security_group_id = var.lb_security_group_id

  # Task
  task_image_url = local.container_image
  task_cpu       = var.cpu
  task_memory    = var.memory

  cpu_architecture = var.cpu_architecture
  desired_count    = var.desired_count

  service_port             = var.container_port
  service_healthcheck_port = var.health_check_port
  service_healthcheck_path = var.health_check_path

  service_healthcheck_interval            = 30
  service_healthcheck_timeout             = 10
  service_healthcheck_healthy_threshold   = 3
  service_healthcheck_unhealthy_threshold = 5

  environment_variables = local.environment_variables

  log_retention_in_days = var.log_retention_days
}

# IAM policy for S3 access
resource "aws_iam_role_policy" "s3_access_policy" {
  name = "${var.service_name}-s3-access"
  role = module.ecs_app.ecs_task_role

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Effect = "Allow"
          Action = [
            "s3:GetObject",
            "s3:PutObject",
            "s3:ListBucket",
            "s3:DeleteObject"
          ]
          Resource = [
            # Kockpit data bucket
            aws_s3_bucket.kockpit_data.arn,
            "${aws_s3_bucket.kockpit_data.arn}/*",
            # Kockpit manifests bucket
            aws_s3_bucket.kockpit_manifests.arn,
            "${aws_s3_bucket.kockpit_manifests.arn}/*"
          ]
        }
      ],
      var.kms_key_arn != null ? [
        {
          Effect   = "Allow"
          Action   = ["kms:GenerateDataKey", "kms:Decrypt"]
          Resource = var.kms_key_arn
        }
      ] : []
    )
  })
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group_rule" "lb_http_from_cloudfront" {
  type              = "ingress"
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  prefix_list_ids   = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  security_group_id = var.lb_security_group_id
  description       = "Allow CloudFront to reach ALB on port 80"
}

# Dedicated HTTP listener for this backend service. Unauthenticated at the
# ALB by design: access control is enforced upstream, at the CloudFront
# distribution + Lambda@Edge in the kockpit-console module, not here.
resource "aws_lb_listener" "http" {
  load_balancer_arn = var.load_balancer_arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = module.ecs_app.aws_lb_target_group.arn
  }

  tags = var.tags
}
