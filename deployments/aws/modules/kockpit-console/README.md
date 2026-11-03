# Kockpit Console Module

This Terraform module deploys the Kockpit Console web application using Amazon S3 for static hosting and CloudFront for global content delivery.

## Features

- **Static Web Hosting**: S3 bucket with proper configuration for single-page applications
- **Global CDN**: CloudFront distribution for fast content delivery worldwide
- **Custom Domains**: Support for custom domain names with SSL certificates
- **API Proxy**: Optional integration with backend ALB for seamless API communication
- **Auto Deployment**: Automatic download and deployment of console UI from GitHub releases
- **SPA Support**: Proper error handling for client-side routing
- **Security**: Origin Access Control (OAC) for secure S3 access

## Usage

### Basic Usage

```hcl
module "kockpit_console" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/kockpit-console?ref=main"

  # Basic configuration
  service_name = "kockpit-console"
  aws_region   = "eu-west-1"

  # Tags
  tags = {
    Environment = "production"
    Project     = "kockpit"
    Team        = "platform"
  }
}
```

### With Custom Domain

```hcl
module "kockpit_console" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/kockpit-console?ref=main"

  service_name        = "kockpit-console"
  bucket_name_prefix  = "my-kockpit-console"

  # Custom domain configuration
  aliases             = ["console.mydomain.com"]
  acm_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/12345678-1234-1234-1234-123456789012"

  tags = {
    Environment = "production"
    Project     = "kockpit"
  }
}
```

### With Backend Integration

```hcl
module "kockpit_console" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/kockpit-console?ref=main"

  service_name = "kockpit-console"

  # Backend ALB integration for API proxy
  backend_alb_domain = "api.mydomain.com"

  # Custom domain for frontend
  aliases             = ["console.mydomain.com"]
  acm_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/12345678-1234-1234-1234-123456789012"

  tags = {
    Environment = "production"
    Project     = "kockpit"
  }
}
```

### Manual Deployment Mode

```hcl
module "kockpit_console" {
  source = "git::https://github.com/mahieddine-ichir/kockpit.git//deployments/aws/modules/kockpit-console?ref=main"

  service_name = "kockpit-console"
  auto_deploy  = false  # Skip automatic download and deployment

  tags = {
    Environment = "production"
    Project     = "kockpit"
  }
}
```

## Requirements

| Name | Version |
|------|---------|
| terraform | >= 1.0 |
| aws | ~> 5.0 |
| archive | ~> 2.0 |
| null | ~> 3.0 |

## Providers

| Name | Version |
|------|---------|
| aws | ~> 5.0 |
| archive | ~> 2.0 |
| null | ~> 3.0 |
| random | n/a |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| aws_region | AWS region where resources will be created | `string` | `"eu-west-1"` | no |
| service_name | Name of the service (used for resource naming) | `string` | `"kockpit-console"` | no |
| kockpit_env | Kockpit environment (dev, staging, prod) | `string` | `"dev"` | no |
| bucket_name_prefix | Prefix for the S3 bucket name (environment will be appended automatically) | `string` | `"kockpit-console"` | no |
| default_root_object | Default root object for CloudFront distribution | `string` | `"index.html"` | no |
| price_class | CloudFront price class | `string` | `"PriceClass_100"` | no |
| aliases | List of CNAMEs (alternate domain names) for the distribution | `list(string)` | `null` | no |
| acm_certificate_arn | ARN of the ACM certificate for custom domains (optional if create_certificate is true) | `string` | `null` | no |
| create_certificate | Whether to create an ACM certificate for custom domains | `bool` | `true` | no |
| backend_alb_domain | Domain name of the backend ALB for API proxy | `string` | `null` | no |
| console_ui_version | Version tag for cache busting when updating the UI | `string` | `"latest"` | no |
| console_ui_download_url | URL to download the console UI distribution zip | `string` | `"https://github.com/mahieddine-ichir/kockpit/releases/download/console-ui-dev-latest/console-ui-dist.zip"` | no |
| auto_deploy | Whether to automatically download and deploy the console UI | `bool` | `true` | no |
| tags | Tags to apply to all resources | `map(string)` | `{"Environment": "production", "Project": "kockpit", "ManagedBy": "terraform"}` | no |

## Outputs

| Name | Description |
|------|-------------|
| s3_bucket_id | ID of the S3 bucket hosting the console UI |
| s3_bucket_arn | ARN of the S3 bucket hosting the console UI |
| cloudfront_distribution_id | ID of the CloudFront distribution |
| cloudfront_domain_name | Domain name of the CloudFront distribution |
| console_url | URL to access the Kockpit console |
| deployment_status | Status of the console deployment |

## Architecture

```
Internet → CloudFront → S3 Bucket (Static Files)
              ↓
         ALB Backend (API Proxy)
```

### Key Features:

1. **S3 Static Hosting**:
   - Versioned bucket for rollback capability
   - Public access blocked (access only via CloudFront)
   - Proper MIME type detection for web assets

2. **CloudFront Distribution**:
   - Global edge locations for fast delivery
   - Custom SSL certificates support
   - SPA-friendly error handling (404 → index.html)
   - Separate cache behaviors for static assets vs API calls

3. **API Integration**:
   - Optional backend ALB integration
   - Proxies requests to your API
   - Proper header forwarding for authentication

4. **Security**:
   - Origin Access Control (OAC) for S3 access
   - HTTPS-only access
   - Geo-restriction support (disabled by default)

## Manual Deployment

If you set `auto_deploy = false`, you can manually upload files to the S3 bucket:

```bash
# Download and extract the console UI
curl -L -o console-ui-dist.zip https://github.com/mahieddine-ichir/kockpit/releases/download/console-ui-dev-latest/console-ui-dist.zip
unzip console-ui-dist.zip

# Upload to S3 (files are at root level, not in a dist/ subdirectory)
aws s3 sync ./ s3://your-bucket-name/ --delete --exclude "*.zip"

# Invalidate CloudFront cache
aws cloudfront create-invalidation --distribution-id YOUR_DISTRIBUTION_ID --paths "/*"
```

## Custom Domain Setup

The module supports custom domains with automatic SSL certificate creation. Here are the setup options:

### Option 1: Use a Domain You Control

If you own a domain and have DNS control through Terraform:

```hcl
module "kockpit_console" {
  # ... other configuration
  aliases             = ["console.yourdomain.com"]
  create_certificate  = true
}
```

### Option 2: Corporate Domain (Manual DNS Setup)

For corporate domains like `kockpit-console.dev.example.com` where DNS is managed externally:

1. **Configure the module:**
```hcl
module "kockpit_console" {
  # ... other configuration
  aliases             = ["kockpit-console.dev.example.com"]
  create_certificate  = true
}
```

2. **Apply Terraform:**
```bash
terraform apply
```

3. **Get certificate validation records:**
```bash
terraform output kockpit_console
# Look for certificate_validation_options
```

4. **Contact your DNS administrator** to add two types of records:

   **a) Certificate Validation (Required for SSL):**
   ```
   Type: CNAME
   Name: _acme-challenge.kockpit-console.dev.example.com
   Value: <validation_record_from_output>
   ```

   **b) Domain Pointing (Required for access):**
   ```
   Type: CNAME
   Name: kockpit-console.dev.example.com
   Value: d123456789.cloudfront.net  # (from cloudfront_domain_name output)
   ```

5. **Wait for certificate validation** (usually 5-10 minutes after DNS records are added)
   - Terraform will automatically wait for certificate validation before creating CloudFront distribution
   - You can monitor progress in AWS ACM console (us-east-1 region)

6. **Test access:**
```bash
curl -I https://kockpit-console.dev.example.com
```

### Option 3: No Custom Domain (Simplest)

Skip custom domains and use the CloudFront URL directly:

```hcl
module "kockpit_console" {
  # ... other configuration
  # Don't set aliases or create_certificate
}
```

Access via: `https://d123456789.cloudfront.net` (from `cloudfront_domain_name` output)

### Troubleshooting Custom Domains

- **Certificate validation failing**: Check DNS records are correctly added and wait 5-10 minutes
- **CloudFront SSL errors**: Ensure certificate is fully validated (ISSUED status) in ACM console (us-east-1 region)
- **Domain not resolving**: Verify CNAME points to CloudFront domain
- **Terraform timeout on certificate validation**: DNS records may not be propagated yet, check with DNS administrator
- **403 Forbidden**: Check S3 bucket policy and CloudFront OAC configuration

## Troubleshooting Auto-Deployment

If the S3 bucket is empty despite `auto_deploy = true`:

1. **Check if the download succeeded**: In your consuming project, look for temp files:
   ```bash
   find .terraform/modules/*/temp -name "*.html" 2>/dev/null
   ```

2. **Verify archive extraction**: Check if files are extracted correctly:
   ```bash
   find .terraform/modules/*/temp -type f | head -10
   ```

3. **Manual deployment workaround**: Use the manual deployment commands above

4. **Debug Terraform resource**: Check the S3 objects resource:
   ```bash
   terraform state list | grep aws_s3_object
   terraform state show 'module.kockpit_console.aws_s3_object.console_files["index.html"]'
   ```

5. **Common issues**:
   - Archive structure changed: Files should be at root level, not in a subdirectory
   - Network issues during download: Check curl command in logs
   - Permissions: Ensure AWS credentials have S3 upload permissions

## Notes

- **S3 Bucket Naming**: Bucket name will be `{bucket_name_prefix}-{kockpit_env}` (e.g., "kockpit-console-dev"). Avoid including environment in the prefix to prevent double environment suffixes.
- **Certificate Requirements**: For custom domains, ACM certificates must be in the `us-east-1` region for CloudFront
- **DNS Configuration**: You'll need to create CNAME/ALIAS records pointing your custom domain to the CloudFront distribution
- **Backend Integration**: When using `backend_alb_domain`, ensure your ALB accepts traffic from CloudFront edge locations
- **Cost Optimization**: Use `PriceClass_100` for cost-effective delivery to US and Europe only
- **Certificate Validation**: DNS validation records must be added to your domain's DNS zone before the certificate becomes active