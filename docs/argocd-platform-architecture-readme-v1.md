# ArgoCD Platform — GitOps and Dynamic Control-Plane Architecture

## High-level summary

The platform uses a **Managed ArgoCD** as the central orchestration layer for multiple ArgoCD control planes.

Key decisions:

1. **Platform-owned configuration lives in Git**
   - Managed ArgoCD bootstrap configuration.
   - Control-plane ApplicationSets.
   - Project/cluster batch ApplicationSets.
   - Helm charts/templates and platform scripts.
   - The Spring Boot routing service can live in the same `argocd-platform` repository.

2. **User-owned state does not live in Git**
   - Users, projects, clusters, applications, Git sources, deployment configuration, and routing information live in PostgreSQL.
   - The routing service exposes read APIs specifically for ArgoCD/ApplicationSet consumption.

3. **Managed ArgoCD owns control-plane lifecycle**
   - Control planes are registered with Managed ArgoCD.
   - The Cluster Generator discovers control planes.
   - Managed ArgoCD installs/upgrades ArgoCD on them using the public ArgoCD Helm chart and Git-hosted values.

4. **Managed ArgoCD materializes user-owned state**
   - Users call the platform API.
   - The routing service performs the primary project-level authorization check.
   - Desired state is stored in PostgreSQL.
   - Managed ArgoCD/ApplicationSets materialize that state into control planes.

5. **Control-plane ArgoCD remains the policy/reconciliation layer**
   - `AppProject` enforces source repositories, destinations, namespaces and resource types.
   - User applications must not be allowed to create ArgoCD control-plane resources or deploy into the `argocd` namespace.
   - The control plane does not need to know the original end-user identity.

6. **Batching limits ApplicationSet payloads and Managed ArgoCD object count**
   - A platform with ~1,000 clusters and ~100 applications per cluster could have ~100,000 user applications.
   - We do not want 100,000 Application objects in Managed ArgoCD.
   - Projects are expected to be much fewer than clusters, roughly <100–150.
   - Project and cluster state is therefore partitioned into batches.
   - A top-level ApplicationSet retrieves only batch metadata.
   - It creates one Application per batch.
   - Each batch Application creates a batch-specific ApplicationSet.
   - The batch ApplicationSet retrieves only that batch's resources.
   - This keeps payloads bounded and allows relatively short `requeueAfterSeconds`.

---

# 1. Repository structure

Suggested structure:

```text
argocd-platform/
├── bootstrap/
│   └── managed-argocd-application.yaml
│
├── managed/
│   └── applicationsets/
│       ├── control-planes.yaml
│       ├── project-batches.yaml
│       └── cluster-batches.yaml
│
├── control-planes/
│   ├── create-local-cluster.sh
│   ├── values/
│   │   ├── default.yaml
│   │   ├── cp-1/default.yaml
│   │   ├── cp-2/default.yaml
│   │   └── ...
│   └── ...
│
├── charts/
│   ├── control-plane/
│   ├── project-batch/
│   ├── cluster-batch/
│   └── application-batch/
│
└── routing-service/
    └── ... Spring Boot application ...
```

The exact layout can evolve. The important principle is that platform-owned resources and scripts are version-controlled together.

---

# 2. Database model

The database is the source of truth for user-owned state.

## 2.1 `users`

| Column | Purpose |
|---|---|
| `id` | User identifier |
| `username` | Platform/LDAP identity |
| `status` | Active/inactive |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

Authentication can remain outside this service if LDAP/SSO is used.

## 2.2 `projects`

| Column | Purpose |
|---|---|
| `id` | Project ID |
| `name` | Project name |
| `description` | Description |
| `status` | Lifecycle state |
| `created_by` | User ID |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

A project is the main platform authorization boundary.

## 2.3 `project_members`

| Column | Purpose |
|---|---|
| `project_id` | Project |
| `user_id` | User |
| `role` | Project-level role |
| `created_at` | Membership timestamp |

The routing service primarily uses this relationship to answer: **Can this user operate on this project?**

## 2.4 `clusters`

| Column | Purpose |
|---|---|
| `id` | Cluster ID |
| `name` | Cluster name |
| `server` | Kubernetes API endpoint/reference |
| `status` | Lifecycle state |
| `control_plane_id` | Current control-plane assignment |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

`control_plane_id` is mutable to support failover/rebalancing.

## 2.5 `project_clusters`

| Column | Purpose |
|---|---|
| `project_id` | Project |
| `cluster_id` | Cluster |
| `status` | Mapping state |
| `created_at` | Mapping timestamp |

## 2.6 `applications`

| Column | Purpose |
|---|---|
| `id` | Stable application ID |
| `name` | Application name |
| `project_id` | Owning project |
| `cluster_id` | Target user cluster |
| `status` | Lifecycle state |
| `generation` | Desired-state generation |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

Application identity should remain stable when its control-plane assignment changes.

## 2.7 `application_sources`

| Column | Purpose |
|---|---|
| `id` | Source ID |
| `application_id` | Application |
| `repo_url` | Git repository |
| `revision` | Branch/tag/commit |
| `path` | Manifest path |
| `chart` | Helm chart if applicable |
| `values` | Helm values/configuration |
| `source_order` | Multi-source ordering |

Supports applications with multiple sources.

## 2.8 `control_planes`

| Column | Purpose |
|---|---|
| `id` | Control-plane ID |
| `name` | Control-plane name |
| `server` | ArgoCD/Kubernetes endpoint/reference |
| `status` | Healthy/unhealthy/draining/etc. |
| `capacity` | Optional scheduling capacity |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

The actual ArgoCD cluster Secret remains a Kubernetes resource; raw credentials do not need to live in this table.

## 2.9 Optional `cluster_control_plane_assignment`

If routing history or controlled failover is needed:

| Column | Purpose |
|---|---|
| `cluster_id` | Cluster |
| `control_plane_id` | Assigned control plane |
| `generation` | Assignment generation |
| `status` | Active/draining/etc. |
| `assigned_at` | Assignment timestamp |

## 2.10 Generation/change tracking

Use generation/version fields on relevant records or a separate change table.

Useful concepts:

```text
project generation
batch generation
application generation
routing generation
```

Example:

```text
DB generation:             124
Routing API generation:    124
ApplicationSet generation: 123
Control-plane state:       122
```

---

# 3. Routing Service APIs

These are internal APIs consumed by ApplicationSet Plugin Generators.

## 3.1 Project-batch discovery

```http
GET /internal/argocd/project-batches
```

Purpose: used by the top-level **Project-Batch ApplicationSet**.

It must not return all applications.

Example response:

```json
[
  {
    "project": "project01",
    "batchCount": 10,
    "generation": 42
  },
  {
    "project": "project02",
    "batchCount": 45,
    "generation": 17
  }
]
```

The plugin expands this into:

```text
project01 / batch01
project01 / batch02
...
project01 / batch10
project02 / batch01
...
project02 / batch45
```

The ApplicationSet then creates:

```text
project01-batch01
project01-batch02
...
project02-batch45
```

## 3.2 Project batch data

```http
GET /internal/argocd/projects/{project}/batches/{batch}
```

Example:

```http
GET /internal/argocd/projects/project01/batches/01
```

Example response:

```json
{
  "project": "project01",
  "batch": "01",
  "generation": 124,
  "applications": [
    {
      "name": "app-001",
      "cluster": "cluster-001",
      "controlPlane": "cp-1",
      "project": "project01",
      "sources": [
        {
          "repoURL": "https://github.com/company/app-001.git",
          "revision": "main",
          "path": "deploy"
        }
      ]
    },
    {
      "name": "app-002",
      "cluster": "cluster-007",
      "controlPlane": "cp-2",
      "project": "project01",
      "sources": [
        {
          "repoURL": "https://github.com/company/app-002.git",
          "revision": "v1.2.0",
          "path": "k8s"
        }
      ]
    }
  ]
}
```

The response is bounded by the configured batch size.

## 3.3 Cluster-batch discovery

```http
GET /internal/argocd/cluster-batches
```

Example response:

```json
[
  {
    "batch": "01",
    "clusterCount": 100,
    "generation": 51
  },
  {
    "batch": "02",
    "clusterCount": 100,
    "generation": 52
  },
  {
    "batch": "03",
    "clusterCount": 100,
    "generation": 53
  }
]
```

With 1,000 clusters and batch size 100, there are 10 cluster batches.

## 3.4 Cluster batch data

```http
GET /internal/argocd/clusters/batches/{batch}
```

Example:

```http
GET /internal/argocd/clusters/batches/01
```

Example response:

```json
{
  "batch": "01",
  "generation": 51,
  "clusters": [
    {
      "name": "cluster-001",
      "server": "https://cluster-001.example.internal",
      "config": {
        "bearerToken": "...",
        "tlsClientConfig": {
          "caData": "..."
        }
      }
    },
    {
      "name": "cluster-002",
      "server": "https://cluster-002.example.internal",
      "config": {
        "bearerToken": "...",
        "tlsClientConfig": {
          "caData": "..."
        }
      }
    }
  ]
}
```

For production, cluster credentials should preferably be supplied through an External Secrets/secret-store mechanism rather than exposing raw credentials through a normal API. Local development can use generated cluster Secret manifests in Git as previously planned.

---

# 4. ApplicationSet hierarchy

The platform uses two major batch pipelines:

```text
PROJECTS
    │
    ▼
Project-Batch ApplicationSet
    │
    ▼
Batch Applications
    │
    ▼
Project-Batch ApplicationSets
    │
    ▼
User Applications on Control Planes


CLUSTERS
    │
    ▼
Cluster-Batch ApplicationSet
    │
    ▼
Batch Applications
    │
    ▼
Cluster-Batch ApplicationSets
    │
    ▼
Cluster registration resources
```

Important ArgoCD rule:

```text
ApplicationSet → Application
Application → ApplicationSet
ApplicationSet → Application
```

An ApplicationSet does not directly create another ApplicationSet. An Application can manage the child ApplicationSet.

---

# 5. Project hierarchy in detail

## Level 1 — Project-Batch ApplicationSet

Managed ArgoCD has one ApplicationSet responsible for discovering project batches:

```text
project-batch-appset
        │
        │ Plugin Generator
        ▼
GET /internal/argocd/project-batches
```

It creates:

```text
project01-batch01
project01-batch02
...
project02-batch45
```

These are Applications in Managed ArgoCD.

## Level 2 — Project batch Application

Example:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: project01-batch01
  namespace: argocd
spec:
  project: platform

  source:
    repoURL: https://github.com/hemant0308/argocd-platform.git
    targetRevision: main
    path: charts/project-batch-applicationset
    helm:
      valuesObject:
        project: project01
        batch: "01"

  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
```

This Application's purpose is to create/manage:

```text
project01-batch01-appset
```

It does not directly contain the 100 user applications.

## Level 3 — Project batch ApplicationSet

The child ApplicationSet is configured with:

```text
project = project01
batch   = 01
```

and calls:

```http
GET /internal/argocd/projects/project01/batches/01
```

It generates the actual user Applications.

## Level 4 — User Application

Example:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: app-001
  namespace: argocd
spec:
  project: project01

  sources:
    - repoURL: https://github.com/company/app-001.git
      targetRevision: main
      path: deploy

  destination:
    server: https://cp-1-argocd.example.internal
    namespace: argocd
```

The destination here is the **control-plane ArgoCD**, not the user cluster.

The complete flow is:

```text
Managed ArgoCD
      │
      ▼
project01-batch01
      │
      ▼
project01-batch01-appset
      │
      ▼
app-001
      │
      ▼
CP-1 ArgoCD
      │
      ▼
User cluster
```

---

# 6. Cluster hierarchy in detail

## Level 1 — Cluster-Batch ApplicationSet

```text
cluster-batch-appset
        │
        │ Plugin Generator
        ▼
GET /internal/argocd/cluster-batches
```

It creates:

```text
cluster-batch01
cluster-batch02
...
cluster-batch10
```

for 1,000 clusters at a batch size of 100.

## Level 2 — Cluster batch Application

Example:

```text
cluster-batch01
```

manages:

```text
cluster-batch01-appset
```

and passes:

```text
batch = 01
```

## Level 3 — Cluster batch ApplicationSet

The child ApplicationSet calls:

```http
GET /internal/argocd/clusters/batches/01
```

and receives only that batch's cluster registration information.

It then generates the cluster registration resources.

Conceptually:

```text
Managed ArgoCD
      │
      ▼
cluster-batch01
      │
      ▼
cluster-batch01-appset
      │
      ▼
cluster registration resources
      │
      ▼
Managed ArgoCD cluster registry
```

The exact final resource can be an ArgoCD cluster Secret or another supported registration mechanism.

---

# 7. Control-plane installation

Control-plane installation remains separate from user project/application state.

Managed ArgoCD has:

```text
control-planes ApplicationSet
```

using the Cluster Generator:

```yaml
generators:
  - clusters:
      selector:
        matchLabels:
          argocd-platform/control-plane: "true"
```

It generates:

```text
argocd-cp-1
argocd-cp-2
argocd-cp-3
```

Each Application installs the public `argo-cd` Helm chart.

Common values:

```text
control-planes/values/default.yaml
```

Cluster-specific values:

```text
control-planes/values/cp-1/default.yaml
control-planes/values/cp-2/default.yaml
control-planes/values/cp-3/default.yaml
```

---

# 8. Complete hierarchy

```text
                              Git
                               │
                               ▼
                       Managed ArgoCD
                               │
              ┌────────────────┼─────────────────┐
              │                │                 │
              ▼                ▼                 ▼
       Control Plane       Project-Batch     Cluster-Batch
       ApplicationSet      ApplicationSet   ApplicationSet
              │                │                 │
              │                │                 │
              ▼                ▼                 ▼
       CP-1 / CP-2 / CP-3   Batch Apps       Batch Apps
                               │                 │
                               ▼                 ▼
                         Batch AppSets       Batch AppSets
                               │                 │
                               ▼                 ▼
                         User Applications   Cluster resources
                               │
                               ▼
                       Control Plane ArgoCD
                               │
                               ▼
                          User Cluster
```

---

# 9. Security model

## Routing Service

The routing service performs the minimal platform-level authorization check:

```text
Can this user operate on Project X?
```

It does not duplicate the full ArgoCD authorization/policy engine.

## Control-plane AppProject

Control-plane ArgoCD `AppProject` remains the resource-policy boundary.

It should enforce:

- allowed source repositories
- allowed destinations
- allowed namespaces
- allowed namespace-scoped resources
- allowed cluster-scoped resources

User applications should not be permitted to:

```text
deploy into argocd namespace
create Application
create ApplicationSet
create AppProject
modify control-plane ArgoCD resources
```

Prefer an allow-list for permitted resource types instead of allowing everything and maintaining a deny list.

## Managed ArgoCD identity

When Managed ArgoCD creates an Application/ApplicationSet on a control plane, it does not propagate the original user's identity.

The control-plane Kubernetes API sees the identity represented by the cluster credential registered in Managed ArgoCD, for example:

```text
system:serviceaccount:kube-system:argocd-manager
```

if that ServiceAccount's token is used.

Therefore:

```text
User
  ↓
Routing Service
  ↓
Managed ArgoCD
  ↓
CP Kubernetes API
```

does not become:

```text
Alice → CP Kubernetes API
```

It becomes:

```text
Managed ArgoCD platform identity → CP Kubernetes API
```

This is why user/project authorization happens at the Routing Service boundary, while AppProject provides the final ArgoCD resource-policy boundary.

For production, the Managed ArgoCD control-plane credential should be narrowed from the broad `cluster-admin` permissions used during local development.

---

# 10. Failover model

Example:

```text
Before:

cluster-001 → CP-1
cluster-002 → CP-1
cluster-003 → CP-1
```

If CP-1 fails:

```text
cluster-001 → CP-2
cluster-002 → CP-2
cluster-003 → CP-2
```

The routing state changes in PostgreSQL.

The batch endpoint reflects the new control-plane assignments.

The batch ApplicationSet reconciles the generated Applications toward CP-2.

The control-plane installation itself can be recreated by Managed ArgoCD.

Therefore the platform's source of truth is not trapped inside CP-1.

---

# 11. Why batching exists

Without batching:

```text
1000 clusters × 100 applications
= 100,000 applications
```

A single project-level or cluster-level ApplicationSet could be forced to process a huge response.

With batching:

```text
Project / Cluster
       │
       ▼
batch metadata
       │
       ▼
N batch Applications
       │
       ▼
N batch ApplicationSets
       │
       ▼
bounded payload per batch
```

Example:

```text
1000 clusters
batch size = 100

→ 10 cluster batches
```

Example:

```text
Project 1
5000 applications
batch size = 100

→ 50 project batches
```

The batch size should remain configurable and should be selected using real ArgoCD performance testing rather than permanently assuming 100.

---

# 12. Latency considerations

The batching design is intended to allow relatively small `requeueAfterSeconds` values.

Top-level endpoints return small payloads:

```text
/project-batches
/cluster-batches
```

Batch endpoints return bounded payloads:

```text
/projects/{project}/batches/{batch}
/clusters/batches/{batch}
```

A new application normally affects only its relevant project batch.

A control-plane reassignment affects the relevant cluster/application batches.

Therefore we avoid repeatedly transferring an entire project or the entire platform just to detect a small change.

Generation/version fields should make propagation observable and can later support more efficient change detection or event-driven refresh.

---

# 13. Key architectural principles

1. Git is the source of truth for platform-owned configuration.
2. PostgreSQL is the source of truth for user-owned desired state.
3. Managed ArgoCD is the central materialization/orchestration layer.
4. Control planes are replaceable execution/reconciliation engines.
5. ApplicationSets are used for dynamic discovery.
6. Plugin Generators retrieve external desired-state information.
7. Batching bounds ApplicationSet payload size.
8. Managed ArgoCD creates Applications/ApplicationSets on control planes.
9. Control-plane AppProjects enforce ArgoCD resource policy.
10. The Routing Service performs only the platform-level project authorization check rather than duplicating ArgoCD policy logic.
11. User applications cannot create ArgoCD control-plane resources.
12. Cluster/control-plane assignment is mutable to support failover.
13. Generation numbers should make reconciliation state observable.
14. Batch size and polling intervals should be chosen from scale testing.

---

# 14. Recommended implementation sequence

```text
1. Managed ArgoCD
        ↓
2. Control-plane Cluster Generator
        ↓
3. Control-plane ArgoCD installation
        ↓
4. Cluster registration batching
        ↓
5. Project batching
        ↓
6. Project-batch ApplicationSet
        ↓
7. Batch Application → Batch ApplicationSet
        ↓
8. Batch ApplicationSet → User Applications
        ↓
9. AppProject policy generation
        ↓
10. Failover/reassignment
        ↓
11. Scale testing
```

The next major technical milestone is one project + one batch end-to-end, then measure:

- plugin response time
- ApplicationSet reconciliation time
- Application creation time
- control-plane reconciliation time
- payload size
- CPU/memory consumption
- behavior when a control-plane assignment changes
