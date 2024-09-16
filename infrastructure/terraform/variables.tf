variable "project_id" {
  description = "The ID of the Google Cloud project"
  type        = string
}

variable "region" {
  description = "The region where resources will be created"
  type        = string
  default     = "us-central1"
}

variable "environment" {
  description = "The environment (dev, staging, prod)"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, staging, prod."
  }
}

variable "service_account_email" {
  description = "The email of the service account to be used"
  type        = string
}

variable "api_versions" {
  description = "Versions of APIs to be enabled"
  type = object({
    compute   = string
    container = string
    storage   = string
    sql       = string
  })
  default = {
    compute   = "v1"
    container = "v1"
    storage   = "v1"
    sql       = "v1beta4"
  }
}

variable "resource_prefix" {
  description = "Prefix to be used for resource names"
  type        = string
  default     = "myapp"
}

variable "scaling_parameters" {
  description = "Parameters for autoscaling"
  type = object({
    min_replicas    = number
    max_replicas    = number
    cpu_utilization = number
  })
  default = {
    min_replicas    = 1
    max_replicas    = 10
    cpu_utilization = 0.7
  }
}

variable "storage_bucket_names" {
  description = "Names of storage buckets to be created"
  type        = list(string)
}

variable "db_instance" {
  description = "Database instance details"
  type = object({
    name           = string
    version        = string
    tier           = string
    disk_size      = number
    backup_enabled = bool
  })
}

# HUMAN ASSISTANCE NEEDED
# The following variables might need additional configuration or validation:
# - Consider adding more specific validation rules for project_id, region, and service_account_email
# - The api_versions object might need to be expanded or adjusted based on the specific APIs used in the project
# - The storage_bucket_names variable might benefit from a default value or additional validation
# - The db_instance object might need more fields depending on the specific database requirements