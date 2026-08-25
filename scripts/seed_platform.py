#!/usr/bin/env python3
"""
seed_platform.py — ArgoCD Platform local environment tool.

Commands:
  seed      Create Kind clusters, register them, create projects + applications.
  teardown  Delete all Kind clusters (with confirmation) and clean up the API.
  startup   Wait for ArgoCD servers to be ready on all CP clusters, then
            port-forward all ArgoCD UIs and print access URLs.

Requirements:
    - kind, kubectl available on PATH
    - Platform service running at http://localhost:8080 (override with --api)
    - ruamel.yaml installed: pip install ruamel.yaml  (for comment-safe values.yaml updates)
      Falls back to PyYAML (pip install pyyaml) which works but strips file comments.

Usage:
    python3 scripts/seed_platform.py seed \\
        --control-planes 3 --destinations 5 --projects 4 --applications 10

    python3 scripts/seed_platform.py teardown

    python3 scripts/seed_platform.py startup
"""

import argparse
import base64
import json
import os
import random
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ──────────────────────────────────────────────────────────────────────────────
# Configuration defaults
# ──────────────────────────────────────────────────────────────────────────────

DEFAULT_API_URL = "http://localhost:8080"
DEFAULT_REPO_URL = "https://github.com/hemant0308/argocd-platform.git"
REPO_REVISION = "main"
CHART_PATH = "charts/sample-app"

SERVICE_ACCOUNT = "argocd-manager"
SA_NAMESPACE = "kube-system"
TOKEN_SECRET = "argocd-manager-token"

MANAGED_ARGOCD_SVC = "argocd-server"                     # service name in the managed cluster
MANAGED_PORT = 8082                                        # local port for managed ArgoCD UI
MANAGED_SVC_PORT = 80                                      # svc port 80 (HTTP) — insecure: true in values.yaml
CP_SVC_PORT = 443                                          # svc port 443 (HTTPS) for CP clusters
CP_BASE_PORT = 8083                                        # first port assigned to cp-1, cp-2, …

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
VALUES_FILE = REPO_ROOT / "argocd" / "managed" / "values.yaml"
BOOTSTRAP_FILE = REPO_ROOT / "bootstrap" / "application.yaml"
MANAGED_ARGOCD_DIR = REPO_ROOT / "bootstrap" / "managed-argocd"

# Set at startup from --api argument
_api_base: str = DEFAULT_API_URL


# ──────────────────────────────────────────────────────────────────────────────
# Logging
# ──────────────────────────────────────────────────────────────────────────────

def log(msg: str) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {msg}")
    print(f"{'=' * 60}")


def info(msg: str) -> None:
    print(f"    → {msg}")


def warn(msg: str) -> None:
    print(f"    ⚠  {msg}", file=sys.stderr)


# ──────────────────────────────────────────────────────────────────────────────
# Shell / kubectl helpers
# ──────────────────────────────────────────────────────────────────────────────

def run_capture(cmd: list, check: bool = True, stdin_text: str = None) -> str:
    """Run a command and return its stripped stdout."""
    result = subprocess.run(
        cmd,
        input=stdin_text,
        capture_output=True,
        text=True,
        check=check,
    )
    return result.stdout.strip()


def run_show(cmd: list, check: bool = True, stdin_text: str = None) -> None:
    """Run a command, letting stdout/stderr flow to the terminal."""
    subprocess.run(
        cmd,
        input=stdin_text,
        text=True,
        check=check,
    )


def kubectl_apply(context: str, yaml_text: str) -> None:
    run_show(["kubectl", "--context", context, "apply", "-f", "-"], stdin_text=yaml_text)


# ──────────────────────────────────────────────────────────────────────────────
# Kind cluster management
# ──────────────────────────────────────────────────────────────────────────────

def kind_cluster_exists(name: str) -> bool:
    output = run_capture(["kind", "get", "clusters"], check=False)
    return name in output.splitlines()


def ensure_kind_cluster(name: str) -> None:
    """Create a Kind cluster if it does not already exist, then wait for Ready."""
    if kind_cluster_exists(name):
        info(f"Kind cluster '{name}' already exists — skipping creation.")
    else:
        info(f"Creating Kind cluster '{name}' …")
        run_show(["kind", "create", "cluster", "--name", name])

    context = f"kind-{name}"
    info(f"Waiting for all nodes to be Ready in '{context}' …")
    run_capture([
        "kubectl", "--context", context,
        "wait", "--for=condition=Ready", "nodes",
        "--all", "--timeout=120s",
    ])


def ensure_service_account(context: str) -> None:
    """
    Idempotently create argocd-manager ServiceAccount, ClusterRoleBinding,
    and long-lived token Secret — mirrors create-local-cluster.sh logic.
    """
    existing = run_capture([
        "kubectl", "--context", context,
        "get", "secret", TOKEN_SECRET,
        "-n", SA_NAMESPACE,
        "--ignore-not-found", "-o", "name",
    ])
    if existing:
        info(f"Token secret '{TOKEN_SECRET}' already exists in '{context}'.")
        return

    info(f"Creating ServiceAccount '{SERVICE_ACCOUNT}' …")
    kubectl_apply(context, f"""
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {SERVICE_ACCOUNT}
  namespace: {SA_NAMESPACE}
""")

    info(f"Creating ClusterRoleBinding '{SERVICE_ACCOUNT}' …")
    kubectl_apply(context, f"""
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: {SERVICE_ACCOUNT}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: {SERVICE_ACCOUNT}
    namespace: {SA_NAMESPACE}
""")

    info(f"Creating token Secret '{TOKEN_SECRET}' …")
    kubectl_apply(context, f"""
apiVersion: v1
kind: Secret
metadata:
  name: {TOKEN_SECRET}
  namespace: {SA_NAMESPACE}
  annotations:
    kubernetes.io/service-account.name: {SERVICE_ACCOUNT}
type: kubernetes.io/service-account-token
""")


# ──────────────────────────────────────────────────────────────────────────────
# Managed ArgoCD bootstrap
# ──────────────────────────────────────────────────────────────────────────────

def bootstrap_managed_argocd() -> None:
    """
    Replicate bootstrap/managed-argocd/install.sh in Python:

      1. helm repo add argo https://argoproj.github.io/argo-helm
      2. helm repo update
      3. kubectl create namespace argocd  (idempotent via --dry-run | apply)
      4. helm upgrade --install argocd argo/argo-cd  \\
             --namespace argocd  \\
             --kube-context kind-managed  \\
             --values bootstrap/managed-argocd/values.yaml
    """
    context = "kind-managed"
    namespace = "argocd"
    values_file = MANAGED_ARGOCD_DIR / "values.yaml"

    if not values_file.exists():
        warn(f"Helm values not found at {values_file} — skipping managed ArgoCD install.")
        return

    info("Adding Argo Helm repository…")
    run_show(["helm", "repo", "add", "argo", "https://argoproj.github.io/argo-helm"], check=False)

    info("Updating Helm repositories…")
    run_show(["helm", "repo", "update"])

    info(f"Creating namespace '{namespace}' (idempotent)…")
    ns_yaml = run_capture([
        "kubectl", "--context", context,
        "create", "namespace", namespace,
        "--dry-run=client", "-o", "yaml",
    ])
    run_show(
        ["kubectl", "--context", context, "apply", "-f", "-"],
        stdin_text=ns_yaml,
    )

    info("Installing managed ArgoCD via Helm…")
    run_show([
        "helm", "upgrade", "--install", "argocd", "argo/argo-cd",
        "--namespace", namespace,
        "--kube-context", context,
        "--values", str(values_file),
    ])

    info("Managed ArgoCD installation complete.")


# ──────────────────────────────────────────────────────────────────────────────
# Token / CA extraction
# ──────────────────────────────────────────────────────────────────────────────

def get_bearer_token(context: str) -> str:
    """Poll up to 30 s for the SA token to be populated by the controller."""
    for attempt in range(30):
        raw = run_capture([
            "kubectl", "--context", context,
            "get", "secret", TOKEN_SECRET,
            "-n", SA_NAMESPACE,
            "-o", "jsonpath={.data.token}",
        ])
        if raw:
            return base64.b64decode(raw).decode()
        time.sleep(1)
    raise RuntimeError(f"Bearer token not generated after 30 s in context '{context}'")


def get_ca_data(cluster_name: str) -> str:
    """Return the base64-encoded CA certificate for the given Kind cluster."""
    kind_context = f"kind-{cluster_name}"
    return run_capture([
        "kubectl", "config", "view", "--raw",
        "-o",
        f"jsonpath={{.clusters[?(@.name=='{kind_context}')].cluster.certificate-authority-data}}",
    ])


# ──────────────────────────────────────────────────────────────────────────────
# Platform API helpers
# ──────────────────────────────────────────────────────────────────────────────

def api_get(path: str):
    with urllib.request.urlopen(f"{_api_base}{path}") as resp:
        return json.loads(resp.read())


def api_post(path: str, data: dict) -> dict:
    body = json.dumps(data).encode()
    req = urllib.request.Request(
        f"{_api_base}{path}",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode()
        raise RuntimeError(f"POST {path} → HTTP {exc.code}: {detail}") from exc


def api_delete(path: str) -> None:
    """Send DELETE to the platform API; silently ignores 404."""
    req = urllib.request.Request(f"{_api_base}{path}", method="DELETE")
    try:
        urllib.request.urlopen(req)
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            warn(f"DELETE {path} → HTTP {exc.code}")


def find_by_name(items: list, name: str):
    return next((i for i in items if i.get("name") == name), None)


# ──────────────────────────────────────────────────────────────────────────────
# argocd/managed/values.yaml updater
# ──────────────────────────────────────────────────────────────────────────────

def update_values_yaml(cp_name: str, server: str, bearer_token: str) -> None:
    """
    Upsert a control-plane cluster entry in argocd/managed/values.yaml,
    preserving all existing comments and formatting.

    Written structure (matches existing cp-1 / cp-2 entries):
        - name: cp-N
          server: https://cp-N-control-plane:6443
          secretType: direct
          direct:
            bearerToken: <token>
            tlsClientConfig:
              insecure: true   ← Kind certs don't cover the cp-N-control-plane SAN
              caData: ""

    Uses ruamel.yaml (comment-safe) if available; falls back to PyYAML with a warning.
    """
    if not VALUES_FILE.exists():
        warn(f"values.yaml not found at {VALUES_FILE} — skipping update.")
        return

    # Base entry dict used by both parsers
    new_entry_data: dict = {
        "name": cp_name,
        "server": server,
        "secretType": "direct",
        "direct": {
            "bearerToken": bearer_token,
            "tlsClientConfig": {
                "insecure": True,
                "caData": "",
            },
        },
    }

    # ── ruamel.yaml (comment-preserving round-trip) ──────────────────────
    try:
        from ruamel.yaml import YAML
        from ruamel.yaml.comments import CommentedMap

        ryaml = YAML()
        ryaml.preserve_quotes = True

        with open(VALUES_FILE) as fh:
            values = ryaml.load(fh)

        if values is None:
            values = CommentedMap()

        clusters = values.setdefault("clusters", [])

        existing_idx = next(
            (j for j, c in enumerate(clusters) if c.get("name") == cp_name),
            None,
        )

        if existing_idx is not None:
            e = clusters[existing_idx]
            e["server"] = server
            e["secretType"] = "direct"
            if "direct" not in e:
                e["direct"] = CommentedMap()
            e["direct"]["bearerToken"] = bearer_token
            if "tlsClientConfig" not in e["direct"]:
                e["direct"]["tlsClientConfig"] = CommentedMap()
            e["direct"]["tlsClientConfig"]["insecure"] = True
            e["direct"]["tlsClientConfig"]["caData"] = ""
            info(f"Updated '{cp_name}' in values.yaml (ruamel.yaml — comments preserved).")
        else:
            clusters.append(CommentedMap(new_entry_data))
            info(f"Appended '{cp_name}' to values.yaml clusters list.")

        with open(VALUES_FILE, "w") as fh:
            ryaml.dump(values, fh)
        return

    except ImportError:
        pass

    # ── PyYAML fallback (strips comments — user is warned) ───────────────
    try:
        import yaml

        warn("ruamel.yaml not found — falling back to PyYAML, which strips file comments.")
        warn("Install with:  pip install ruamel.yaml")

        with open(VALUES_FILE) as fh:
            values = yaml.safe_load(fh) or {}

        clusters = values.setdefault("clusters", [])
        entry = find_by_name(clusters, cp_name)

        if entry is None:
            clusters.append(new_entry_data)
            info(f"Added new entry '{cp_name}' to values.yaml clusters list.")
        else:
            entry.update(new_entry_data)
            info(f"Updated entry '{cp_name}' in values.yaml.")

        with open(VALUES_FILE, "w") as fh:
            yaml.dump(values, fh, default_flow_style=False, allow_unicode=True, sort_keys=False)

        info(f"values.yaml written — '{cp_name}' credentials stored.")
        return

    except ImportError:
        pass

    warn("Neither ruamel.yaml nor PyYAML is installed — cannot update values.yaml.")
    warn(f"Manually add/update '{cp_name}' in {VALUES_FILE}")
    warn(f"  bearerToken: {bearer_token[:40]}…")


def remove_from_values_yaml(cp_names: list) -> None:
    """
    Remove named entries from the clusters list in values.yaml.
    Uses ruamel.yaml (comment-preserving) if available, else PyYAML.
    """
    if not cp_names or not VALUES_FILE.exists():
        return

    name_set = set(cp_names)

    # ── ruamel.yaml ──────────────────────────────────────────────────────
    try:
        from ruamel.yaml import YAML

        ryaml = YAML()
        ryaml.preserve_quotes = True

        with open(VALUES_FILE) as fh:
            values = ryaml.load(fh)

        if values and "clusters" in values:
            before = len(values["clusters"])
            values["clusters"] = [c for c in values["clusters"] if c.get("name") not in name_set]
            removed = before - len(values["clusters"])
            if removed:
                with open(VALUES_FILE, "w") as fh:
                    ryaml.dump(values, fh)
                info(f"Removed {removed} entry/entries from values.yaml (ruamel.yaml).")
        return

    except ImportError:
        pass

    # ── PyYAML fallback ───────────────────────────────────────────────────
    try:
        import yaml

        with open(VALUES_FILE) as fh:
            values = yaml.safe_load(fh) or {}

        if "clusters" in values:
            before = len(values["clusters"])
            values["clusters"] = [c for c in values["clusters"] if c.get("name") not in name_set]
            if len(values["clusters"]) < before:
                with open(VALUES_FILE, "w") as fh:
                    yaml.dump(values, fh, default_flow_style=False, allow_unicode=True, sort_keys=False)
                info("Removed CP entries from values.yaml (PyYAML).")
        return

    except ImportError:
        pass

    warn("Cannot clean up values.yaml — neither ruamel.yaml nor PyYAML installed.")


# ──────────────────────────────────────────────────────────────────────────────
# Phase 1 — Control Planes
# ──────────────────────────────────────────────────────────────────────────────

def phase_control_planes(count: int) -> list:
    """
    For each cp-1 … cp-N:
      1. Create/reuse Kind cluster.
      2. Extract bearerToken.
      3. Upsert entry in argocd/managed/values.yaml (direct secretType).
      4. Register control plane via POST /api/v1/control-planes (idempotent).
    """
    log(f"Phase 1 — Control Planes  (target: {count})")
    records = []
    existing_cps = api_get("/api/v1/control-planes")

    for i in range(1, count + 1):
        name = f"cp-{i}"
        server = f"https://{name}-control-plane:6443"
        context = f"kind-{name}"

        print(f"\n  [{i}/{count}] {name}")

        ensure_kind_cluster(name)
        ensure_service_account(context)

        bearer_token = get_bearer_token(context)

        info(f"bearerToken: {bearer_token[:40]}…")

        # Write credentials into values.yaml so managed ArgoCD can reach the CP.
        # insecure=true / caData="" matches the existing Kind cluster entries —
        # Kind cert SANs don't cover the cp-N-control-plane Docker DNS hostname.
        update_values_yaml(name, server, bearer_token)

        # Register in platform DB (idempotent by name)
        existing = find_by_name(existing_cps, name)
        if existing:
            info(f"Control plane '{name}' already registered (id={existing['id']}).")
            records.append(existing)
        else:
            record = api_post("/api/v1/control-planes", {
                "name": name,
                "server": server,
            })
            info(f"Registered control plane '{name}' — id={record['id']}")
            records.append(record)
            existing_cps.append(record)

    return records


# ──────────────────────────────────────────────────────────────────────────────
# Phase 2 — Destination Clusters
# ──────────────────────────────────────────────────────────────────────────────

def phase_destinations(count: int, cp_records: list) -> list:
    """
    For each dest-1 … dest-N:
      1. Create/reuse Kind cluster.
      2. Extract bearerToken.
      3. Register cluster via POST /api/v1/clusters with random CP assignment
         (EXPLICIT algorithm) and auth credentials (idempotent by name).
    """
    log(f"Phase 2 — Destination Clusters  (target: {count})")
    records = []
    existing_clusters = api_get("/api/v1/clusters")

    for i in range(1, count + 1):
        name = f"dest-{i}"
        server = f"https://{name}-control-plane:6443"
        context = f"kind-{name}"

        print(f"\n  [{i}/{count}] {name}")

        ensure_kind_cluster(name)
        ensure_service_account(context)

        bearer_token = get_bearer_token(context)

        # Idempotent — use existing if already onboarded
        existing = find_by_name(existing_clusters, name)
        if existing:
            info(f"Cluster '{name}' already registered (id={existing['id']}).")
            records.append(existing)
            continue

        # Randomly pick one control plane to own this destination cluster
        cp = random.choice(cp_records)
        info(f"Assigning '{name}' to control plane '{cp['name']}'")

        record = api_post("/api/v1/clusters", {
            "name": name,
            "server": server,
            "controlPlane": cp["name"],
            "assignmentAlgorithm": "EXPLICIT",
            "auth": {
                "bearerToken": bearer_token,
                # insecure=true: Kind cert SANs don't cover the dest-N-control-plane hostname
                "tlsClientConfig": {
                    "insecure": True,
                    "caData": "",
                },
            },
        })
        info(f"Registered cluster '{name}' — id={record['id']}  cp={cp['name']}")
        records.append(record)
        existing_clusters.append(record)

    return records


# ──────────────────────────────────────────────────────────────────────────────
# Phase 3 — Projects
# ──────────────────────────────────────────────────────────────────────────────

def phase_projects(count: int, dest_records: list) -> list:
    """
    For each project1 … projectN:
      - Assign 1–3 randomly selected destination clusters.
      - POST /api/v1/projects (idempotent — skip if name already exists).
    """
    log(f"Phase 3 — Projects  (target: {count})")
    records = []

    if not dest_records:
        warn("No destination clusters available — projects will be created without cluster assignments.")

    existing_projects = api_get("/api/v1/projects")

    for i in range(1, count + 1):
        name = f"project{i}"
        print(f"\n  [{i}/{count}] {name}")

        existing = find_by_name(existing_projects, name)
        if existing:
            info(f"Project '{name}' already exists (id={existing['id']}) — skipping.")
            records.append(existing)
            continue

        # Pick random 1–3 destination clusters (skip assignment if none available)
        if not dest_records:
            selected = []
        else:
            k = random.randint(1, min(3, len(dest_records)))
            selected = random.sample(dest_records, k)

        cluster_names = [c["name"] for c in selected]
        info(f"Assigning clusters ({len(selected)}): {cluster_names}")

        record = api_post("/api/v1/projects", {
            "name": name,
            "description": f"Auto-generated project {i}",
            "clusters": [{"id": c["id"], "name": c["name"]} for c in selected],
        })
        info(f"Created project '{name}' — id={record['id']}")
        records.append(record)
        existing_projects.append(record)

    return records


# ──────────────────────────────────────────────────────────────────────────────
# Phase 4 — Applications
# ──────────────────────────────────────────────────────────────────────────────

def phase_applications(count: int, project_records: list, repo_url: str) -> list:
    """
    For each app-0001 … app-NNNN:
      - Pick a random project (that has ≥1 cluster).
      - Pick a random cluster within that project.
      - POST /api/v1/applications pointing to charts/sample-app with the app
        name passed as helm valuesObject.name (used as the Deployment name).

    Note: the API appends a 5-char hex suffix to the stored name, e.g.
    app-0001 → app-0001-3f2a1. The suffixed name is printed for reference.
    """
    log(f"Phase 4 — Applications  (target: {count})")
    records = []

    # Build cluster-name → cluster-id lookup from a fresh list
    all_clusters = api_get("/api/v1/clusters")
    cluster_id_by_name = {c["name"]: c["id"] for c in all_clusters}

    # Refresh project data (includes cluster assignments returned as ProjectClusterItem)
    all_projects = api_get("/api/v1/projects")
    created_project_names = {p["name"] for p in project_records}
    # Keep only projects we created that have at least one cluster
    eligible = [
        p for p in all_projects
        if p["name"] in created_project_names and p.get("clusters")
    ]

    if not eligible:
        warn("No projects with cluster assignments found — cannot create applications.")
        return records

    for i in range(1, count + 1):
        app_name = f"app-{i:04d}"
        print(f"\n  [{i}/{count}] {app_name}")

        project = random.choice(eligible)
        project_clusters = project.get("clusters", [])

        if not project_clusters:
            warn(f"Project '{project['name']}' has no clusters — skipping this app.")
            continue

        cluster_name = random.choice(project_clusters)["name"]
        cluster_id = cluster_id_by_name.get(cluster_name)

        if cluster_id is None:
            warn(f"Cluster '{cluster_name}' not found in cluster list — skipping.")
            continue

        info(f"project={project['name']}  cluster={cluster_name}")

        record = api_post("/api/v1/applications", {
            "name": app_name,
            "projectId": project["id"],
            "clusterId": cluster_id,
            "sources": [
                {
                    "repoURL": repo_url,
                    "path": CHART_PATH,
                    "targetRevision": REPO_REVISION,
                    "helm": {
                        "valuesObject": {
                            "name": app_name,
                        },
                    },
                }
            ],
        })
        stored_name = record.get("name", app_name)
        info(f"Created '{stored_name}'  (id={record['id']})")
        records.append(record)

    return records


# ──────────────────────────────────────────────────────────────────────────────
# Teardown
# ──────────────────────────────────────────────────────────────────────────────

def phase_teardown(skip_api_cleanup: bool = False, clean_values: bool = False) -> None:
    """
    1. List all Kind clusters.
    2. Show them and ask for confirmation.
    3. Delete each Kind cluster.
    4. Remove cp-* entries from argocd/managed/values.yaml.
    5. Delete all resources from the platform API (apps → projects → clusters → CPs).
    """
    log("Teardown — Deleting Kind clusters")

    raw = run_capture(["kind", "get", "clusters"], check=False)
    all_clusters = [c.strip() for c in raw.splitlines() if c.strip()]

    if not all_clusters:
        info("No Kind clusters found — nothing to delete.")
        return

    print("\n  The following Kind clusters will be deleted:\n")
    for c in sorted(all_clusters):
        print(f"    •  {c}")
    print()

    confirm = input("  Type 'yes' to confirm deletion: ").strip().lower()
    if confirm != "yes":
        print("\n  Aborted — no clusters were deleted.")
        return

    print()
    for cluster in sorted(all_clusters):
        info(f"Deleting Kind cluster '{cluster}' …")
        run_show(["kind", "delete", "cluster", "--name", cluster], check=False)

    # Remove CP credentials from values.yaml only when explicitly requested
    cp_names = [c for c in all_clusters if re.match(r"^cp-\d+$", c)]
    if cp_names and clean_values:
        info(f"Removing entries from values.yaml: {cp_names}")
        remove_from_values_yaml(cp_names)
    elif cp_names:
        info(f"Kept values.yaml entries for: {cp_names}  (pass --clean-values to remove)")

    # Clean up platform API
    if not skip_api_cleanup:
        log("Teardown — Cleaning up platform API")
        try:
            # Order matters: apps → projects → clusters → control-planes
            for app in api_get("/api/v1/applications"):
                api_delete(f"/api/v1/applications/{app['id']}")
                info(f"Deleted application  '{app['name']}'")

            for proj in api_get("/api/v1/projects"):
                api_delete(f"/api/v1/projects/{proj['id']}")
                info(f"Deleted project      '{proj['name']}'")

            for cl in api_get("/api/v1/clusters"):
                api_delete(f"/api/v1/clusters/{cl['id']}")
                info(f"Deleted cluster      '{cl['name']}'")

            for cp in api_get("/api/v1/control-planes"):
                api_delete(f"/api/v1/control-planes/{cp['id']}")
                info(f"Deleted control plane '{cp['name']}'")

        except Exception as exc:
            warn(f"API cleanup failed: {exc}")
            warn("You may need to clean up API resources manually.")

    log("Teardown complete")


# ──────────────────────────────────────────────────────────────────────────────
# Startup — port-forward ArgoCD UIs
# ──────────────────────────────────────────────────────────────────────────────

def wait_for_argocd_server(context: str, cp_name: str, timeout: int = 600) -> bool:
    """
    Poll until the argocd-server pod is Ready in the given context.
    Prints a progress line every 15 s.
    Returns True on success, False on timeout.
    """
    deadline = time.time() + timeout
    last_report = 0.0

    while time.time() < deadline:
        result = subprocess.run(
            [
                "kubectl", "--context", context,
                "wait", "pod",
                "--for=condition=Ready",
                "-l", "app.kubernetes.io/name=argocd-server",
                "-n", "argocd",
                "--timeout=5s",
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            info(f"[{cp_name}] argocd-server is Ready ✓")
            return True

        now = time.time()
        if now - last_report >= 15:
            remaining = int(deadline - now)
            print(
                f"    ↺  [{cp_name}] waiting for argocd-server pod… ({remaining}s left)",
                flush=True,
            )
            last_report = now

        time.sleep(5)

    return False


def get_argocd_initial_password(context: str) -> str:
    """
    Retrieve the ArgoCD initial admin password for the given kubectl context.
    Tries 'argocd admin initial-password' first; falls back to reading the
    argocd-initial-admin-secret via kubectl.
    Returns the password string, or '<unavailable>' on failure.
    """
    # Primary: argocd CLI
    result = subprocess.run(
        ["argocd", "admin", "initial-password", "-n", "argocd", "--context", context],
        capture_output=True, text=True,
    )
    if result.returncode == 0:
        # First non-empty line is the password
        for line in result.stdout.splitlines():
            line = line.strip()
            if line:
                return line

    # Fallback: kubectl secret
    result = subprocess.run(
        [
            "kubectl", "--context", context,
            "get", "secret", "argocd-initial-admin-secret",
            "-n", "argocd",
            "-o", "jsonpath={.data.password}",
        ],
        capture_output=True, text=True,
    )
    if result.returncode == 0 and result.stdout.strip():
        return base64.b64decode(result.stdout.strip()).decode()

    return "<unavailable>"


def _port_in_use(port: int) -> bool:
    """Return True if something is already listening on 127.0.0.1:<port>."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", port))
            return False
        except OSError:
            return True


def _start_port_forward(context: str, svc: str, local_port: int, svc_port: int = 443) -> subprocess.Popen:
    cmd = [
        "kubectl", "--context", context,
        "port-forward", f"svc/{svc}",
        "-n", "argocd",
        f"{local_port}:{svc_port}",
    ]
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def phase_startup(managed_cluster: str = "managed") -> None:
    """
    1. Detect managed + all cp-* Kind clusters.
    2. Wait for argocd-server pods to be Ready in every CP cluster
       (the user commits + pushes separately to trigger ArgoCD sync).
    3. Launch kubectl port-forward for each cluster.
    4. Print the URL table and keep port-forwards alive (auto-restart on crash).

    Port assignments:
        managed  → {MANAGED_PORT}  (http://manager.localhost:{MANAGED_PORT})   ← HTTP (insecure: true)
        cp-1     → {CP_BASE_PORT}  (https://cp1.localhost:{CP_BASE_PORT})       ← HTTPS
        cp-2     → {CP_BASE_PORT+1}  ...
    """
    log("Startup — Port-forwarding ArgoCD servers")

    raw = run_capture(["kind", "get", "clusters"], check=False)
    all_clusters = [c.strip() for c in raw.splitlines() if c.strip()]

    # Sort CP clusters numerically
    cp_clusters = sorted(
        [c for c in all_clusters if re.match(r"^cp-\d+$", c)],
        key=lambda c: int(c.split("-")[1]),
    )
    has_managed = managed_cluster in all_clusters

    if not has_managed and not cp_clusters:
        warn(f"No '{managed_cluster}' or cp-* clusters found in Kind.")
        return

    # ── Wait for ArgoCD on CP clusters ──────────────────────────────────
    if cp_clusters:
        print(f"\n  Found {len(cp_clusters)} control plane cluster(s): {cp_clusters}")
        print("\n  Waiting for the ArgoCD server pod to become Ready on each CP cluster.")
        print("  In another terminal: git add / git commit / git push so managed ArgoCD")
        print("  syncs and installs the CP ArgoCD Helm chart.\n")

        ready_cps = []
        for cp in cp_clusters:
            context = f"kind-{cp}"
            ok = wait_for_argocd_server(context, cp, timeout=600)
            if ok:
                ready_cps.append(cp)
            else:
                warn(f"ArgoCD not ready in '{cp}' after 10 min — it will be skipped.")
    else:
        ready_cps = []

    # ── Build port-forward table ─────────────────────────────────────────
    # (cluster_name, local_port, svc_name, svc_port, scheme)
    forwards_spec: list = []

    if has_managed:
        # managed ArgoCD runs with server.insecure: true → HTTP on svc port 80
        forwards_spec.append((managed_cluster, MANAGED_PORT, MANAGED_ARGOCD_SVC, MANAGED_SVC_PORT, "http"))

    for i, cp in enumerate(ready_cps):
        svc = f"control-plane-{cp}-argocd-server"
        forwards_spec.append((cp, CP_BASE_PORT + i, svc, CP_SVC_PORT, "https"))

    if not forwards_spec:
        warn("Nothing to port-forward.")
        return

    # ── Launch port-forwards ─────────────────────────────────────────────
    forwards = []   # (cluster_name, local_port, svc_name, svc_port, scheme, Popen|None)
    for cluster_name, local_port, svc, svc_port, scheme in forwards_spec:
        context = f"kind-{cluster_name}"
        if _port_in_use(local_port):
            info(f"Port {local_port} already bound — reusing existing forward for '{cluster_name}'.")
            forwards.append((cluster_name, local_port, svc, svc_port, scheme, None))
        else:
            proc = _start_port_forward(context, svc, local_port, svc_port)
            forwards.append((cluster_name, local_port, svc, svc_port, scheme, proc))
            info(f"port-forward :{local_port} → {cluster_name} svc/{svc}:{svc_port} (pid={proc.pid})")

    # Give the OS a moment to bind the ports
    time.sleep(2)

    # ── Print URL table ──────────────────────────────────────────────────
    log("ArgoCD UIs — port-forward active")
    for cluster_name, local_port, svc, svc_port, scheme, _ in forwards:
        if cluster_name == managed_cluster:
            label = "Managed"
            # Use 127.0.0.1 for HTTP: browsers enforce HSTS on 'localhost' hostnames
            # and upgrade HTTP→HTTPS, which breaks the connection to the insecure server.
            url = f"{scheme}://127.0.0.1:{local_port}"
        else:
            # cp-1 → cp1, cp-2 → cp2
            short = cluster_name.replace("-", "")
            label = cluster_name
            url = f"{scheme}://{short}.localhost:{local_port}"
        print(f"  {label:<10}  -->  {url}")

    # ── Print initial admin passwords ───────────────────────────────────
    print()
    log("ArgoCD initial admin passwords")
    for cluster_name, local_port, svc, svc_port, scheme, _ in forwards:
        context = f"kind-{cluster_name}"
        password = get_argocd_initial_password(context)
        if cluster_name == managed_cluster:
            label = "Managed"
        else:
            label = cluster_name
        print(f"  {label:<10}  username: admin   password: {password}")

    print("\n  Port-forwards are running. Press Ctrl+C to stop.\n")

    # ── Keep alive with auto-restart ─────────────────────────────────────
    try:
        while True:
            time.sleep(5)
            for i, (cluster_name, local_port, svc, svc_port, scheme, proc) in enumerate(forwards):
                if proc is None:
                    # Port was pre-bound; nothing to monitor
                    continue
                if proc.poll() is not None:
                    context = f"kind-{cluster_name}"
                    warn(
                        f"Port-forward for '{cluster_name}' (:{local_port}) exited "
                        f"(rc={proc.returncode}) — restarting…"
                    )
                    new_proc = _start_port_forward(context, svc, local_port, svc_port)
                    forwards[i] = (cluster_name, local_port, svc, svc_port, scheme, new_proc)
    except KeyboardInterrupt:
        print("\n\n  Stopping port-forwards…")
        for *_, proc in forwards:
            if proc is not None:
                proc.terminate()
        print("  Done.\n")


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

# ──────────────────────────────────────────────────────────────────────────────
# Local git server
# ──────────────────────────────────────────────────────────────────────────────

def _get_kind_host_ip() -> str:
    """
    Return the IP of the Docker host reachable from inside Kind containers.
    Tries 'host.docker.internal' first (macOS/Windows Docker Desktop), then
    falls back to the gateway IP of the 'kind' Docker network (Linux).
    """
    try:
        ip = socket.gethostbyname("host.docker.internal")
        return "host.docker.internal"
    except socket.gaierror:
        pass

    result = subprocess.run(
        ["docker", "network", "inspect", "kind",
         "--format", "{{range .IPAM.Config}}{{.Gateway}} {{end}}"],
        capture_output=True, text=True,
    )
    if result.returncode == 0:
        # Take the first IPv4 gateway
        for token in result.stdout.split():
            token = token.strip()
            if token and ":" not in token:   # skip IPv6
                return token

    return "172.17.0.1"   # Docker default bridge fallback


def phase_local_git(port: int = 9418) -> None:
    """
    Start a git daemon serving the local repo so ArgoCD can clone and pull
    without pushing to GitHub.

    - git daemon serves COMMITTED changes (git commit; no push needed).
    - The daemon is reachable from inside Kind containers via the Docker
      host gateway IP.
    - Port-forward is kept alive; Ctrl+C stops it.
    """
    log("Local Git Server")

    if _port_in_use(port):
        info(f"Port {port} is already in use — git daemon may already be running.")
        host_ip = _get_kind_host_ip()
        git_url = f"git://{host_ip}:{port}/{REPO_ROOT.name}"
        _print_local_git_summary(git_url, port, already_running=True)
        return

    _check_tools("git")

    # git daemon --base-path=<parent>  lets clients use git://<host>:<port>/<repo_name>
    cmd = [
        "git", "daemon",
        "--reuseaddr",
        f"--port={port}",
        f"--base-path={REPO_ROOT.parent}",
        "--export-all",
        "--verbose",
        str(REPO_ROOT),
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    info(f"git daemon started — pid={proc.pid}  port={port}")

    host_ip = _get_kind_host_ip()
    git_url = f"git://{host_ip}:{port}/{REPO_ROOT.name}"
    _print_local_git_summary(git_url, port)

    print("  Press Ctrl+C to stop the git daemon.\n")
    try:
        while True:
            time.sleep(5)
            if proc.poll() is not None:
                warn(f"git daemon exited unexpectedly (rc={proc.returncode}) — restarting…")
                proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                info(f"git daemon restarted — pid={proc.pid}")
    except KeyboardInterrupt:
        print("\n\n  Stopping git daemon…")
        proc.terminate()
        print("  Done.\n")


def _print_local_git_summary(git_url: str, port: int, already_running: bool = False) -> None:
    status = "(already running)" if already_running else ""
    log(f"Local Git Server ready {status}")
    print(f"  Repo URL : {git_url}")
    print(f"  Port     : {port}")
    print()
    print("  Use this URL with the seed command:")
    print(f"    python3 scripts/seed_platform.py seed ... --repo {git_url}")
    print()
    print("  Patch the managed ArgoCD Application to read from local git:")
    print(f"    kubectl patch application argocd -n argocd --context kind-managed \\")
    print(f"      --type merge \\")
    print(f"      -p '{{\"spec\":{{\"source\":{{\"repoURL\":\"{git_url}\"}}}}}}'")
    print()
    print("  Workflow: edit files → git commit (no push!) → ArgoCD auto-syncs")
    print()


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

def _check_tools(*tools: str) -> None:
    for tool in tools:
        result = subprocess.run(["which", tool], capture_output=True)
        if result.returncode != 0:
            print(f"ERROR: '{tool}' not found on PATH.", file=sys.stderr)
            sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="ArgoCD Platform local environment tool.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # ── seed ─────────────────────────────────────────────────────────────────
    seed_p = subparsers.add_parser(
        "seed",
        help="Seed platform with Kind clusters, projects, and applications.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 scripts/seed_platform.py seed \\
      --control-planes 2 --destinations 4 --projects 3 --applications 10

  python3 scripts/seed_platform.py seed \\
      --control-planes 1 --destinations 2 --projects 2 --applications 5 \\
      --repo https://github.com/myorg/argocd-platform.git
        """,
    )
    seed_p.add_argument(
        "--control-planes", type=int, required=True, metavar="N",
        help="Number of control plane clusters to create (cp-1 … cp-N)",
    )
    seed_p.add_argument(
        "--destinations", type=int, required=True, metavar="N",
        help="Number of destination clusters to create (dest-1 … dest-N)",
    )
    seed_p.add_argument(
        "--projects", type=int, required=True, metavar="N",
        help="Number of projects to create (project1 … projectN)",
    )
    seed_p.add_argument(
        "--applications", type=int, default=0, metavar="N",
        help="Number of applications to create (app-0001 … app-NNNN). Omit to skip.",
    )
    seed_p.add_argument(
        "--repo", metavar="URL", default=DEFAULT_REPO_URL,
        help=f"Git repo URL for application sources (default: {DEFAULT_REPO_URL})",
    )
    seed_p.add_argument(
        "--api", metavar="URL", default=DEFAULT_API_URL,
        help=f"Platform API base URL (default: {DEFAULT_API_URL})",
    )

    # ── teardown ──────────────────────────────────────────────────────────────
    td_p = subparsers.add_parser(
        "teardown",
        help="Delete all Kind clusters and clean up the platform API.",
    )
    td_p.add_argument(
        "--api", metavar="URL", default=DEFAULT_API_URL,
        help=f"Platform API base URL (default: {DEFAULT_API_URL})",
    )
    td_p.add_argument(
        "--skip-api-cleanup", action="store_true",
        help="Skip deleting resources from the platform API.",
    )
    td_p.add_argument(
        "--clean-values", action="store_true",
        help="Also remove cp-* entries from argocd/managed/values.yaml (off by default).",
    )

    # ── startup ───────────────────────────────────────────────────────────────
    su_p = subparsers.add_parser(
        "startup",
        help=(
            "Wait for ArgoCD servers to be ready on all CP clusters, "
            "then port-forward all ArgoCD UIs."
        ),
    )
    su_p.add_argument(
        "--managed-cluster", metavar="NAME", default="managed",
        help="Kind cluster name for the managed ArgoCD (default: managed)",
    )

    args = parser.parse_args()

    # ── Dispatch ──────────────────────────────────────────────────────────────
    global _api_base

    if args.command == "seed":
        _api_base = args.api

        print("""
╔══════════════════════════════════════════════════════════╗
║          ArgoCD Platform — Local Environment Seeder      ║
╚══════════════════════════════════════════════════════════╝""")
        app_summary = f"app-0001 … app-{args.applications:04d}" if args.applications else "skipped"
        print(f"""
  Managed cluster : managed  (always created first)
  Control planes  : {args.control_planes}  (cp-1 … cp-{args.control_planes})
  Destinations    : {args.destinations}  (dest-1 … dest-{args.destinations})
  Projects        : {args.projects}  (project1 … project{args.projects})
  Applications    : {args.applications if args.applications else 0}  ({app_summary})
  Repo URL        : {args.repo}
  API             : {_api_base}
  Values file     : {VALUES_FILE}
""")

        _check_tools("kind", "kubectl", "helm")

        try:
            api_get("/api/v1/control-planes")
        except Exception as exc:
            print(f"ERROR: Cannot reach platform API at {_api_base}: {exc}", file=sys.stderr)
            sys.exit(1)

        try:
            log("Phase 0 — Managed Cluster")
            ensure_kind_cluster("managed")
            bootstrap_managed_argocd()
            if BOOTSTRAP_FILE.exists():
                info(f"Applying bootstrap Application: {BOOTSTRAP_FILE.name}")
                run_show([
                    "kubectl", "apply",
                    "-f", str(BOOTSTRAP_FILE),
                    "--context", "kind-managed",
                ])
            else:
                warn(f"Bootstrap file not found at {BOOTSTRAP_FILE} — skipping.")

            cp_records = phase_control_planes(args.control_planes)
            dest_records = phase_destinations(args.destinations, cp_records)
            project_records = phase_projects(args.projects, dest_records)
            if args.applications:
                app_records = phase_applications(args.applications, project_records, args.repo)
            else:
                app_records = []
                info("--applications not provided — skipping application creation.")
        except KeyboardInterrupt:
            print("\n\nInterrupted.", file=sys.stderr)
            sys.exit(130)
        except RuntimeError as exc:
            print(f"\nERROR: {exc}", file=sys.stderr)
            sys.exit(1)

        log("Seeding complete")
        print(f"""
  Control planes registered : {len(cp_records)}
  Destination clusters      : {len(dest_records)}
  Projects created          : {len(project_records)}
  Applications created      : {len(app_records)}

  Note: application names have a 5-char hex suffix appended by the
  platform API (e.g. app-0001 → app-0001-3f2a1). See output above.
""")
        log("ArgoCD initial admin passwords")
        # Managed ArgoCD is installed by now; CP ArgoCD installs after git push + sync
        all_seeded_clusters = ["managed"] + [f"cp-{i}" for i in range(1, args.control_planes + 1)]
        for cluster_name in all_seeded_clusters:
            context = f"kind-{cluster_name}"
            label = "Managed" if cluster_name == "managed" else cluster_name
            password = get_argocd_initial_password(context)
            print(f"  {label:<10}  username: admin   password: {password}")
        if args.control_planes:
            print("\n  Note: CP passwords show '<unavailable>' until ArgoCD is synced by managed ArgoCD")
            print("        (run 'seed startup' after git push to see them once ready)")
        print()

    elif args.command == "teardown":
        _api_base = args.api

        _check_tools("kind", "kubectl")

        try:
            phase_teardown(skip_api_cleanup=args.skip_api_cleanup, clean_values=args.clean_values)
        except KeyboardInterrupt:
            print("\n\nInterrupted.", file=sys.stderr)
            sys.exit(130)

    elif args.command == "startup":
        _check_tools("kind", "kubectl")

        try:
            phase_startup(managed_cluster=args.managed_cluster)
        except KeyboardInterrupt:
            print("\n\nInterrupted.", file=sys.stderr)
            sys.exit(130)


if __name__ == "__main__":
    main()
