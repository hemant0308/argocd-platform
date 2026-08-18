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
TOKEN_SECRET="${SERVICE_ACCOUNT}-token"

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
    kind create cluster --name "${CLUSTER_NAME}"
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
# 4. Create ServiceAccount, ClusterRoleBinding, and token Secret
#    Skipped if the token Secret already exists — prints
#    the existing token and CA instead.
# ------------------------------------------------------------

SECRET_EXISTS="$(
    kubectl --context "${CONTROL_PLANE_CONTEXT}" \
        get secret "${TOKEN_SECRET}" \
        -n "${SERVICE_ACCOUNT_NAMESPACE}" \
        --ignore-not-found \
        -o name
)"

if [[ -n "${SECRET_EXISTS}" ]]; then
    log "ServiceAccount token Secret already exists — skipping setup"
    echo "Token Secret '${TOKEN_SECRET}' found in namespace '${SERVICE_ACCOUNT_NAMESPACE}'."
else
    log "Creating ServiceAccount"

    kubectl --context "${CONTROL_PLANE_CONTEXT}" apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${SERVICE_ACCOUNT_NAMESPACE}
EOF

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

    log "Creating ServiceAccount token Secret"

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
fi

# ------------------------------------------------------------
# 5. Wait for / read token
# ------------------------------------------------------------

log "Reading ServiceAccount token"

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
# 6. Get Kubernetes CA
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
# 7. Print cluster registration manifest to console
#    (no files are written)
# ------------------------------------------------------------

log "Cluster registration manifest"

cat <<EOF

---
# ArgoCD cluster Secret for: ${CLUSTER_NAME}
# Save to: argocd/managed/clusters/${CLUSTER_NAME}.yaml
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
# 8. Summary
# ------------------------------------------------------------

log "Control plane ready"

echo
echo "Cluster          : ${CLUSTER_NAME}"
echo "Context          : ${CONTROL_PLANE_CONTEXT}"
echo "Container        : ${CLUSTER_CONTAINER}"
echo "IP               : ${CLUSTER_IP}"
echo "ArgoCD server    : ${CLUSTER_SERVER}"
echo
echo "Bearer token:"
echo
echo "  ${TOKEN}"
echo
echo "CA certificate (base64):"
echo
echo "  ${CA_DATA}"
echo
echo "Next steps:"
echo
echo "  1. Copy the manifest above into:"
echo "       argocd/managed/clusters/${CLUSTER_NAME}.yaml"
echo
echo "  2. Create cluster-specific values (if needed):"
echo "       control-planes/values/${CLUSTER_NAME}/default.yaml"
echo
echo "  3. Commit and push:"
echo "       git add argocd/managed/clusters/${CLUSTER_NAME}.yaml"
echo "       git commit -m \"Add control plane ${CLUSTER_NAME}\""
echo "       git push"
echo
