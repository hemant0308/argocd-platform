#!/usr/bin/env python3
"""
seed_platform.py — ArgoCD Platform local environment seeder.

Creates Kind clusters for control planes and destinations, registers them with
the platform API, creates projects with random cluster assignments, and creates
applications pointing to charts/sample-app.

Requirements:
    - kind, kubectl available on PATH
    - Platform service running at http://localhost:8080 (override with --api)
    - ruamel.yaml installed: pip install ruamel.yaml  (for comment-safe values.yaml updates)
      Falls back to PyYAML (pip install pyyaml) which works but strips file comments.

Usage:
    python3 scripts/seed_platform.py \\
        --control-planes 3 \\
        --destinations 5 \\
        --projects 4 \\
        --applications 10 \\
        [--repo https://github.com/org/argocd-platform.git] \\
        [--api http://localhost:8080]
"""

import argparse
import base64
import json
import os
import random
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

SCRIPT_DIR = Path(__file__).resolve().parent
VALUES_FILE = SCRIPT_DIR.parent / "argocd" / "managed" / "values.yaml"

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


# ──────────────────────────────────────────────────────────────────────────────
# Phase 1 — Control Planes
# ──────────────────────────────────────────────────────────────────────────────

def phase_control_planes(count: int) -> list:
    """
    For each cp-1 … cp-N:
      1. Create/reuse Kind cluster.
      2. Extract bearerToken + CA.
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
      2. Extract bearerToken + CA.
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
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Seed the ArgoCD platform with Kind clusters, projects, and applications.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 scripts/seed_platform.py \\
      --control-planes 2 --destinations 4 --projects 3 --applications 10

  python3 scripts/seed_platform.py \\
      --control-planes 1 --destinations 2 --projects 2 --applications 5 \\
      --repo https://github.com/myorg/argocd-platform.git
        """,
    )
    parser.add_argument(
        "--control-planes", type=int, required=True, metavar="N",
        help="Number of control plane clusters to create (cp-1 … cp-N)",
    )
    parser.add_argument(
        "--destinations", type=int, required=True, metavar="N",
        help="Number of destination clusters to create (dest-1 … dest-N)",
    )
    parser.add_argument(
        "--projects", type=int, required=True, metavar="N",
        help="Number of projects to create (project1 … projectN)",
    )
    parser.add_argument(
        "--applications", type=int, required=True, metavar="N",
        help="Number of applications to create (app-0001 … app-NNNN)",
    )
    parser.add_argument(
        "--repo", metavar="URL", default=DEFAULT_REPO_URL,
        help=f"Git repo URL for application sources (default: {DEFAULT_REPO_URL})",
    )
    parser.add_argument(
        "--api", metavar="URL", default=DEFAULT_API_URL,
        help=f"Platform API base URL (default: {DEFAULT_API_URL})",
    )
    args = parser.parse_args()

    # Apply global API base URL
    global _api_base
    _api_base = args.api

    print("""
╔══════════════════════════════════════════════════════════╗
║          ArgoCD Platform — Local Environment Seeder      ║
╚══════════════════════════════════════════════════════════╝""")
    print(f"""
  Control planes  : {args.control_planes}  (cp-1 … cp-{args.control_planes})
  Destinations    : {args.destinations}  (dest-1 … dest-{args.destinations})
  Projects        : {args.projects}  (project1 … project{args.projects})
  Applications    : {args.applications}  (app-0001 … app-{args.applications:04d})
  Repo URL        : {args.repo}
  API             : {_api_base}
  Values file     : {VALUES_FILE}
""")

    # Validate prerequisites
    for tool in ("kind", "kubectl"):
        result = subprocess.run(["which", tool], capture_output=True)
        if result.returncode != 0:
            print(f"ERROR: '{tool}' not found on PATH.", file=sys.stderr)
            sys.exit(1)

    # Validate API reachability
    try:
        api_get("/api/v1/control-planes")
    except Exception as exc:
        print(f"ERROR: Cannot reach platform API at {_api_base}: {exc}", file=sys.stderr)
        sys.exit(1)

    try:
        cp_records = phase_control_planes(args.control_planes)
        dest_records = phase_destinations(args.destinations, cp_records)
        project_records = phase_projects(args.projects, dest_records)
        app_records = phase_applications(args.applications, project_records, args.repo)
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


if __name__ == "__main__":
    main()
