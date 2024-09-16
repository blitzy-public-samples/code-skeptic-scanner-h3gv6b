# Output values for the infrastructure deployment

# Backend API URL
output "backend_api_url" {
  description = "URL of the deployed backend API"
  value       = google_cloud_run_service.backend_api.status[0].url
}

# Cloud Function URLs
output "image_processing_function_url" {
  description = "URL of the image processing Cloud Function"
  value       = google_cloudfunctions_function.image_processing.https_trigger_url
}

output "notification_function_url" {
  description = "URL of the notification Cloud Function"
  value       = google_cloudfunctions_function.notification.https_trigger_url
}

# Database connection strings
output "postgres_connection_string" {
  description = "Connection string for the PostgreSQL database"
  value       = google_sql_database_instance.main.connection_name
  sensitive   = true
}

output "redis_connection_string" {
  description = "Connection string for the Redis cache"
  value       = google_redis_instance.cache.host
  sensitive   = true
}

# Storage bucket URLs
output "user_uploads_bucket_url" {
  description = "URL of the user uploads storage bucket"
  value       = google_storage_bucket.user_uploads.url
}

output "processed_images_bucket_url" {
  description = "URL of the processed images storage bucket"
  value       = google_storage_bucket.processed_images.url
}

# Pub/Sub topic names
output "image_processing_topic_name" {
  description = "Name of the Pub/Sub topic for image processing"
  value       = google_pubsub_topic.image_processing.name
}

output "notification_topic_name" {
  description = "Name of the Pub/Sub topic for notifications"
  value       = google_pubsub_topic.notifications.name
}

# IAM role ARNs
output "backend_service_account_email" {
  description = "Email of the service account for the backend API"
  value       = google_service_account.backend_service_account.email
}

output "cloud_function_service_account_email" {
  description = "Email of the service account for Cloud Functions"
  value       = google_service_account.cloud_function_service_account.email
}

# HUMAN ASSISTANCE NEEDED
# The following outputs may need to be adjusted based on the actual resource names and types used in the Terraform configuration.
# Please review and modify as necessary to match the exact resources defined in your infrastructure.

output "vpc_network_name" {
  description = "Name of the VPC network"
  value       = google_compute_network.main_vpc.name
}

output "load_balancer_ip" {
  description = "IP address of the load balancer"
  value       = google_compute_global_address.default.address
}