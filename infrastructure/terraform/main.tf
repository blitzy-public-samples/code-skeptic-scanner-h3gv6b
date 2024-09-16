# Main Terraform configuration file for provisioning cloud resources

# Provider configuration
provider "google" {
  project = var.project_id
  region  = var.region
}

# Google Cloud Run service for backend API
resource "google_cloud_run_service" "backend_api" {
  name     = "backend-api"
  location = var.region

  template {
    spec {
      containers {
        image = var.backend_image
      }
    }
  }

  traffic {
    percent         = 100
    latest_revision = true
  }
}

# Google Cloud Functions for tweet monitoring
resource "google_cloudfunctions_function" "tweet_monitor" {
  name        = "tweet-monitor"
  description = "Function to monitor tweets"
  runtime     = "python39"

  available_memory_mb   = 256
  source_archive_bucket = google_storage_bucket.function_bucket.name
  source_archive_object = google_storage_bucket_object.function_zip.name
  trigger_http          = true
  entry_point           = "monitor_tweets"
}

# Google Cloud Firestore database
resource "google_firestore_database" "default" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"
}

# Google Cloud Storage buckets
resource "google_storage_bucket" "function_bucket" {
  name     = "${var.project_id}-function-bucket"
  location = var.region
}

resource "google_storage_bucket" "data_bucket" {
  name     = "${var.project_id}-data-bucket"
  location = var.region
}

# Google Cloud Pub/Sub topics and subscriptions
resource "google_pubsub_topic" "tweet_topic" {
  name = "tweet-topic"
}

resource "google_pubsub_subscription" "tweet_subscription" {
  name  = "tweet-subscription"
  topic = google_pubsub_topic.tweet_topic.name

  ack_deadline_seconds = 20
}

# Google Cloud IAM roles and permissions
resource "google_project_iam_member" "function_invoker" {
  project = var.project_id
  role    = "roles/cloudfunctions.invoker"
  member  = "serviceAccount:${google_service_account.function_account.email}"
}

resource "google_service_account" "function_account" {
  account_id   = "function-service-account"
  display_name = "Function Service Account"
}

# Networking configuration
resource "google_compute_network" "vpc_network" {
  name                    = "tweet-analyzer-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "tweet-analyzer-subnet"
  ip_cidr_range = "10.0.0.0/24"
  region        = var.region
  network       = google_compute_network.vpc_network.id
}

resource "google_compute_firewall" "allow_internal" {
  name    = "allow-internal"
  network = google_compute_network.vpc_network.name

  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }

  source_ranges = ["10.0.0.0/24"]
}

# HUMAN ASSISTANCE NEEDED
# The following variables need to be defined in a separate variables.tf file:
# - var.project_id
# - var.region
# - var.backend_image
# Also, consider adding more specific IAM roles and permissions based on the exact requirements of your application.