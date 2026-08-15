#!/usr/bin/env bash

set -euo pipefail

CLUSTER_CONTEXT="kind-managed"
NAMESPACE="argocd"
RELEASE_NAME="argocd"

echo "Using Kubernetes context: ${CLUSTER_CONTEXT}"

kubectl config use-context "${CLUSTER_CONTEXT}"

echo "Adding Argo Helm repository..."
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update

echo "Creating namespace..."
kubectl create namespace "${NAMESPACE}" \
  --dry-run=client \
  -o yaml | kubectl apply -f -

echo "Installing Managed ArgoCD..."

helm upgrade --install "${RELEASE_NAME}" argo/argo-cd \
  --namespace "${NAMESPACE}" \
  --values "$(dirname "$0")/values.yaml"

echo "Managed ArgoCD installation completed."