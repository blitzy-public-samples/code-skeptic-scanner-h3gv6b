#!/bin/bash

set -e

# Build frontend assets
echo "Building frontend assets..."
cd frontend
npm run build
cd ..

# Run tests
echo "Running tests..."
npm test

# Package backend application
echo "Packaging backend application..."
cd backend
npm run build
gcloud builds submit --tag gcr.io/code-skeptic-scanner/backend

# Deploy backend to Google Cloud Run
echo "Deploying backend to Google Cloud Run..."
gcloud run deploy code-skeptic-backend \
  --image gcr.io/code-skeptic-scanner/backend \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated

# Deploy frontend to Google Cloud Storage
echo "Deploying frontend to Google Cloud Storage..."
gsutil rsync -R frontend/build gs://code-skeptic-scanner-frontend

# Update Cloud CDN
echo "Updating Cloud CDN..."
gcloud compute url-maps invalidate-cdn-cache code-skeptic-frontend-lb \
  --path "/*"

# Configure Cloud Functions
echo "Configuring Cloud Functions..."
gcloud functions deploy code-skeptic-analyzer \
  --runtime nodejs14 \
  --trigger-http \
  --allow-unauthenticated

# Set up Pub/Sub topics and subscriptions
echo "Setting up Pub/Sub topics and subscriptions..."
gcloud pubsub topics create code-analysis-requests
gcloud pubsub subscriptions create code-analysis-sub --topic code-analysis-requests

# Update Firestore security rules
echo "Updating Firestore security rules..."
firebase deploy --only firestore:rules

# Verify deployment status
echo "Verifying deployment status..."
gcloud run services describe code-skeptic-backend --platform managed --region us-central1
gsutil ls gs://code-skeptic-scanner-frontend
gcloud functions describe code-skeptic-analyzer
gcloud pubsub topics list
gcloud pubsub subscriptions list
firebase deploy --only firestore:rules --dry-run

echo "Deployment completed successfully!"