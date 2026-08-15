#!/usr/bin/env bash

set -euo pipefail

# ------------------------------------------------------------
# Configuration
# ------------------------------------------------------------

CLUSTER_NAME="${1:?Usage: $0 <cluster-name>}"

CONTROL_PLANE_CONTEXT="kind-${CLUSTER_NAME}"
CLUSTER_CONTAINER="${CLUSTER_NAME}-control-plane"

SERVICE_ACCOUNT="argocd-manager"
SERVICE_ACCOUNT_NAMESPACE="kube-system"

# Repository-relative paths
CLUSTER_MANIFEST_DIR="argocd/managed/clusters"
CLUSTER_MANIFEST="${CLUSTER_MANIFEST_DIR}/${CLUSTER_NAME}.yaml"

VALUES_DIR="control-planes/values"
CLUSTER_VALUES_DIR="${VALUES_DIR}/${CLUSTER_NAME}"
CLUSTER_VALUES_FILE="${CLUSTER_VALUES_DIR}/default.yaml"

CLUSTER_SERVER="https://${CLUSTER_CONTAINER}:6443"

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------

log() {
    echo
    echo "============================================================"
    echo "$1"
    echo "============================================================"
}

# ------------------------------------------------------------
# 1. Create Kind cluster if it doesn't exist
# ------------------------------------------------------------

log "Checking Kind cluster: ${CLUSTER_NAME}"

if kind get clusters | grep -qx "${CLUSTER_NAME}"; then
    echo "Cluster ${CLUSTER_NAME} already exists."
else
    echo "Creating Kind cluster..."

    kind create cluster \
        --name "${CLUSTER_NAME}"
fi

# ------------------------------------------------------------
# 2. Wait for Kubernetes
# ------------------------------------------------------------

log "Waiting for Kubernetes cluster"

kubectl --context "${CONTROL_PLANE_CONTEXT}" wait \
    --for=condition=Ready nodes \
    --all \
    --timeout=120s

kubectl --context "${CONTROL_PLANE_CONTEXT}" get nodes

# ------------------------------------------------------------
# 3. Verify Docker container
# ------------------------------------------------------------

log "Checking control-plane container"

if ! docker inspect "${CLUSTER_CONTAINER}" >/dev/null 2>&1; then
    echo "ERROR: Docker container '${CLUSTER_CONTAINER}' not found."
    exit 1
fi

CLUSTER_IP="$(
    docker inspect \
        -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
        "${CLUSTER_CONTAINER}"
)"

echo "Cluster container : ${CLUSTER_CONTAINER}"
echo "Cluster IP        : ${CLUSTER_IP}"
echo "Cluster server    : ${CLUSTER_SERVER}"

# ------------------------------------------------------------
# 4. Create ServiceAccount
# ------------------------------------------------------------

log "Creating ServiceAccount"

kubectl --context "${CONTROL_PLANE_CONTEXT}" apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${SERVICE_ACCOUNT_NAMESPACE}
EOF

# ------------------------------------------------------------
# 5. Create ClusterRoleBinding
#
# Local POC uses cluster-admin.
# ------------------------------------------------------------

log "Creating ClusterRoleBinding"

kubectl --context "${CONTROL_PLANE_CONTEXT}" apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: ${SERVICE_ACCOUNT}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: ${SERVICE_ACCOUNT}
    namespace: ${SERVICE_ACCOUNT_NAMESPACE}
EOF

# ------------------------------------------------------------
# 6. Create long-lived ServiceAccount token Secret
# ------------------------------------------------------------

log "Creating ServiceAccount token Secret"

TOKEN_SECRET="${SERVICE_ACCOUNT}-token"

kubectl --context "${CONTROL_PLANE_CONTEXT}" apply -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${TOKEN_SECRET}
  namespace: ${SERVICE_ACCOUNT_NAMESPACE}
  annotations:
    kubernetes.io/service-account.name: ${SERVICE_ACCOUNT}
type: kubernetes.io/service-account-token
EOF

# ------------------------------------------------------------
# 7. Wait for token
# ------------------------------------------------------------

log "Waiting for ServiceAccount token"

TOKEN=""

for i in {1..30}; do

  TOKEN="$(
      kubectl \
          --context "${CONTROL_PLANE_CONTEXT}" \
          get secret "${TOKEN_SECRET}" \
          -n "${SERVICE_ACCOUNT_NAMESPACE}" \
          -o jsonpath='{.data.token}' \
          | base64 --decode
  )"

    if [[ -n "${TOKEN}" ]]; then
        break
    fi

    sleep 1
done

if [[ -z "${TOKEN}" ]]; then
    echo "ERROR: ServiceAccount token was not generated."
    exit 1
fi

# ------------------------------------------------------------
# 8. Get Kubernetes CA
# ------------------------------------------------------------

log "Getting Kubernetes CA"


CA_DATA="$(
    kubectl config view \
        --raw \
        -o jsonpath="{.clusters[?(@.name=='${CONTROL_PLANE_CONTEXT}')].cluster.certificate-authority-data}"
)"

if [[ -z "${CA_DATA}" ]]; then
    echo "ERROR: Could not obtain Kubernetes CA certificate."
    exit 1
fi

# ------------------------------------------------------------
# 9. Generate cluster registration manifest
# ------------------------------------------------------------

log "Generating cluster registration manifest"

mkdir -p "${CLUSTER_MANIFEST_DIR}"

cat > "${CLUSTER_MANIFEST}" <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${CLUSTER_NAME}
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: cluster
    argocd-platform/control-plane: "true"
type: Opaque
stringData:
  name: ${CLUSTER_NAME}
  server: ${CLUSTER_SERVER}
  config: |
    {
      "bearerToken": "${TOKEN}",
      "tlsClientConfig": {
        "insecure": false,
        "caData": "${CA_DATA}"
      }
    }
EOF

# ------------------------------------------------------------
# 10. Generate cluster-specific values directory
# ------------------------------------------------------------

log "Generating control-plane values"

mkdir -p "${CLUSTER_VALUES_DIR}"

if [[ ! -f "${CLUSTER_VALUES_FILE}" ]]; then

    cat > "${CLUSTER_VALUES_FILE}" <<EOF
# Cluster-specific ArgoCD values for ${CLUSTER_NAME}.
#
# Common configuration is maintained in:
# control-planes/values/default.yaml
#
# Values in this file override the common configuration.

EOF

    echo "Created ${CLUSTER_VALUES_FILE}"
else
    echo "${CLUSTER_VALUES_FILE} already exists. Keeping existing configuration."
fi

# ------------------------------------------------------------
# 11. Summary
# ------------------------------------------------------------

log "Control plane created"

echo
echo "Cluster:"
echo "  ${CLUSTER_NAME}"
echo
echo "Kubernetes context:"
echo "  ${CONTROL_PLANE_CONTEXT}"
echo
echo "Cluster container:"
echo "  ${CLUSTER_CONTAINER}"
echo
echo "Cluster IP:"
echo "  ${CLUSTER_IP}"
echo
echo "ArgoCD server:"
echo "  ${CLUSTER_SERVER}"
echo
echo "Cluster registration:"
echo "  ${CLUSTER_MANIFEST}"
echo
echo "Cluster values:"
echo "  ${CLUSTER_VALUES_FILE}"
echo
echo "Next:"
echo
echo "  git add ${CLUSTER_MANIFEST} ${CLUSTER_VALUES_FILE}"
echo "  git commit -m \"Add control plane ${CLUSTER_NAME}\""
echo "  git push"
echo