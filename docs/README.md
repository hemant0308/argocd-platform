# ArgoCD Platform — Architecture and Design Summary

## 1. High-level architecture

The platform uses a **Managed ArgoCD** as the central orchestration layer for multiple ArgoCD control planes.

```text
                         User
                           │
                           ▼
                    Routing Service
                           │
                    PostgreSQL state
                           │
                           ▼
                    Managed ArgoCD
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       CP-1              CP-2              CP-3
          │                │                │
          ▼                ▼                ▼
     User Clusters     User Clusters     User Clusters
```

The main design principles are:

1. Platform-owned configuration is stored in Git.
2. User-owned desired state is stored in PostgreSQL.
3. Managed ArgoCD materializes user-owned state onto control planes.
4. Control planes are treated as replaceable execution/reconciliation engines.
5. ApplicationSet Plugin Generators are used to obtain dynamic state from the Routing Service.
6. Stable **partitions** are used to control payload size and ArgoCD resource count.
7. List Generator is **not** used to manufacture N Applications from a numeric count.
8. The Routing Service returns explicit partition descriptors; ApplicationSet creates one Application per returned descriptor.
9. Control-plane AppProjects remain the ArgoCD resource-policy boundary.
10. Routing Service performs only the platform-level user-to-project authorization check.

---

# 2. Repository structure

Platform-owned configuration remains in the Git repository.

Suggested structure:

```text
argocd-platform/
├── bootstrap/
│   └── managed-argocd-application.yaml
│
├── managed/
│   └── applicationsets/
│       ├── control-planes.yaml
│       ├── project-partitions.yaml
│       └── cluster-partitions.yaml
│
├── control-planes/
│   ├── create-local-cluster.sh
│   ├── values/
│   │   ├── default.yaml
│   │   ├── cp-1/
│   │   │   └── default.yaml
│   │   ├── cp-2/
│   │   │   └── default.yaml
│   │   └── ...
│   └── ...
│
├── charts/
│   ├── control-plane/
│   ├── project-partition/
│   ├── cluster-partition/
│   └── application-partition/
│
└── routing-service/
    └── ... Spring Boot application ...
```

The exact directory layout can evolve. The important principle is that platform-owned resources, Helm values and local setup scripts are version-controlled together.

---

# 3. Ownership model

## Git

Git contains resources owned by the platform itself:

- Managed ArgoCD bootstrap
- Control-plane installation configuration
- Control-plane-specific Helm values
- Platform ApplicationSets
- Helm charts/templates
- Local cluster creation/registration scripts

## PostgreSQL

PostgreSQL is the source of truth for user-owned state:

- users
- projects
- project memberships
- clusters
- cluster/project relationships
- applications
- application Git sources
- application configuration
- control-plane assignment
- project partitions
- cluster partitions
- application partitions

User applications, projects and clusters should not be manually maintained in the Git repository.

---

# 4. Database model

## 4.1 `users`

| Column | Purpose |
|---|---|
| `id` | User identifier |
| `username` | Platform/LDAP identity |
| `status` | Active/inactive |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

Authentication may be provided by an external identity system.

---

## 4.2 `projects`

| Column | Purpose |
|---|---|
| `id` | Project ID |
| `name` | Project name |
| `description` | Description |
| `status` | Lifecycle state |
| `created_by` | User ID |
| `project_partition_id` | Assigned project partition |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

A project is the primary platform-level authorization boundary.

---

## 4.3 `project_members`

| Column | Purpose |
|---|---|
| `project_id` | Project |
| `user_id` | User |
| `role` | Project-level role |
| `created_at` | Membership timestamp |

The Routing Service uses this relationship for the primary user authorization check:

> Can this user operate on this project?

---

## 4.4 `clusters`

| Column | Purpose |
|---|---|
| `id` | Cluster ID |
| `name` | Cluster name (globally unique; immutable after creation) |
| `server` | Kubernetes API endpoint/reference |
| `status` | Lifecycle state |
| `cluster_partition_id` | Assigned cluster partition |
| `control_plane_id` | Current control-plane assignment |
| `namespaces` | JSONB array of namespace names / glob patterns the cluster registration is scoped to. `null` means cluster-level (unrestricted) access — the ArgoCD default. Used to build `spec.destinations` on AppProjects. |
| `labels` | JSONB key/value map for selector-based cluster routing |
| `auth` | JSONB free-form authentication credentials for the Kubernetes API server (e.g. `{"bearerToken": "..."}` for bearer auth). `null` for in-cluster or externally managed credentials. Stored and returned verbatim — the platform does not validate or interpret its shape. |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

`control_plane_id` is mutable so clusters can be moved between control planes during failover/rebalancing.

> **Auth storage note:** The `auth` JSONB on `clusters` stores credentials for *user* Kubernetes clusters — i.e., the clusters that ArgoCD deploys user applications to. It is passed through to the ArgoCD cluster `Secret` via the `cluster-registration` Helm chart. Credentials for ArgoCD control planes themselves are not stored in the database; they remain in Kubernetes Secrets managed outside this service.

---

## 4.5 `project_clusters`

| Column | Purpose |
|---|---|
| `project_id` | Project |
| `cluster_id` | Cluster |
| `status` | Mapping state |
| `created_at` | Mapping timestamp |

This represents which clusters are associated with which projects.

---

## 4.6 `applications`

| Column | Purpose |
|---|---|
| `id` | Stable application ID |
| `name` | Application name — see uniqueness note below |
| `project_id` | Owning project |
| `cluster_id` | Target user cluster |
| `application_partition_id` | Stable application partition |
| `status` | Lifecycle state |
| `generation` | Desired-state generation counter; incremented on each desired-state update |
| `sources` | JSONB array of ArgoCD source objects (`repoURL`, `revision`, `path`, `chart`, `helm`, etc.). Free-form — any shape ArgoCD accepts is valid without schema changes. |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

> **Application name uniqueness:** The DB enforces a `(project_id, name)` unique constraint.
> On creation the service appends a 5-character lowercase hex suffix to the caller-supplied base name
> (e.g. `my-app` → `my-app-3f2a1`). This suffix is returned in the create response and becomes the
> stable identity used in ArgoCD. The name cannot be changed after creation.

> **Design decision:** `control_plane_id` is intentionally absent from the `applications` table.
> The control plane is derived transitively via `application → cluster → control_plane`.
> Moving an application to a different control plane is achieved by updating `cluster.control_plane_id`,
> which propagates automatically — no redundant FK on each application record is needed.

Application partition assignment is independent of the cluster's control-plane assignment.

An application's partition does not change when its cluster is reassigned to a different control plane.

---

## 4.7 `application_sources` — superseded

> **Removed in migration v1.0.3.**
> The `application_sources` table was dropped and replaced by the `sources` JSONB column on `applications` (§4.6).
>
> The motivation: a fixed relational schema for sources requires a schema migration for every new ArgoCD
> source field. A JSONB array stores any source shape ArgoCD accepts, including multi-source configurations,
> without schema changes. The application layer is responsible for passing the array through unchanged.

---

## 4.8 `control_planes`

| Column | Purpose |
|---|---|
| `id` | Control-plane ID |
| `name` | Control-plane name |
| `server` | ArgoCD server / Kubernetes API endpoint (used for ArgoCD API calls and cluster registration) |
| `status` | Healthy/unhealthy/draining/etc. |
| `endpoint` | ArgoCD web UI base URL (e.g. `https://argocd.example.com`). Used to construct navigation links to ArgoCD resources (applications, cluster settings, etc.). Nullable — omit for control planes where the web UI URL is not yet known. |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

The actual ArgoCD cluster Secret remains a Kubernetes resource. Raw credentials do not need to be stored in this table.

---

# 5. Partition model

Partitioning is a first-class part of the design.

We have three independent partition dimensions:

```text
project_partitions
cluster_partitions
application_partitions
```

A partition is a **stable reconciliation partition**, not a temporary calculation.

The purpose is to:

- bound API payload size
- reduce ApplicationSet reconciliation scope
- reduce the number of resources managed by one ApplicationSet
- avoid large responses from the Routing Service
- prevent unnecessary movement of existing resources when resources are added/deleted
- make the architecture independently scalable

---

# 6. `project_partitions`

Projects are assigned to persistent project partitions.

Example:

```text
project-001 ──► project-partition-01
project-002 ──► project-partition-01
...
project-100 ──► project-partition-01

project-101 ──► project-partition-02
...
```

Suggested table:

```text
project_partitions
------------------
id
partition_number
status
generation
created_at
updated_at
```

The project table contains:

```text
project_partition_id
```

The number of project partitions is not fixed.

The platform should support scaling from:

```text
10 projects
```

to:

```text
10,000+ projects
```

without changing the architecture.

A configurable target partition size determines when a new partition is created.

The target is not a strict invariant.

---

# 7. `cluster_partitions`

Clusters are assigned to persistent cluster partitions.

Example:

```text
cluster-001 ──► cluster-partition-01
cluster-002 ──► cluster-partition-01
...
cluster-100 ──► cluster-partition-01

cluster-101 ──► cluster-partition-02
...
```

Suggested table:

```text
cluster_partitions
------------------
id
partition_number
status
generation
created_at
updated_at
```

The cluster table contains:

```text
cluster_partition_id
```

The architecture should support arbitrary numbers of clusters.

For example:

```text
1000 clusters
partition target = 100

→ 10 cluster partitions
```

---

# 8. `application_partitions`

Applications are assigned to persistent application partitions.

This is deliberately a separate table because an application partition itself is the lookup boundary for applications.

Suggested table:

```text
application_partitions
----------------------
id
partition_number
status
generation
created_at
updated_at
```

The application table contains:

```text
application_partition_id
```

Example:

```text
application-001 ──► application-partition-01
application-002 ──► application-partition-01
...
application-100 ──► application-partition-01

application-101 ──► application-partition-02
...
```

The Routing Service can therefore directly query:

```text
application_partition_id
```

without needing the project identifier to determine the partition contents.

---

# 9. Stable partition assignment

Partition membership must **not** be dynamically recalculated on every API request.

Bad approach:

```text
sort all applications
    ↓
take every 100
    ↓
calculate partitions
```

Example:

```text
Before:

partition-01:
  app-001 ... app-100

partition-02:
  app-101 ... app-200
```

Delete `app-050`.

Dynamic rebatching could produce:

```text
partition-01:
  app-001 ... app-101

partition-02:
  app-102 ... app-201
```

This causes unrelated applications to move between partitions and can result in unnecessary ApplicationSet reconciliation and resource churn.

Instead:

```text
Delete app-050

partition-01:
  app-001 ... app-049
  app-051 ... app-100

partition-02:
  app-101 ... app-200
```

Existing applications stay in their original partition.

---

# 10. Partition growth policy

Partition size should be configurable.

For example:

```text
target application partition size = 100
target cluster partition size     = 100
target project partition size     = 100
```

These are target/max-new-assignment values, not strict requirements.

Example:

```text
partition-01 → 100
partition-02 → 97
```

A new application goes to partition-02:

```text
partition-02 → 98
```

When all existing partitions reach their configured target, a new partition is created.

Do not rebalance existing resources merely because the total resource count changed.

---

# 11. Empty partitions

Deleting the final resource in a partition should not necessarily delete the partition immediately.

For example:

```text
application-partition-03
    app-201
```

After deleting `app-201`:

```text
application-partition-03
    <empty>
```

Keeping empty partitions avoids unnecessary creation/deletion churn.

A future lifecycle policy can support:

```text
ACTIVE
EMPTY
DRAINING
DELETED
```

and controlled cleanup of long-empty partitions.

---

# 12. Partition and control-plane assignment are independent

This is important.

An application has:

```text
project
application partition
cluster  (control plane is derived via cluster → control_plane)
```

These represent different concepts:

| Attribute | Meaning |
|---|---|
| Project | Ownership/authorization |
| Application Partition | ArgoCD reconciliation/scaling partition |
| Cluster | Deployment destination (carries the `control_plane_id` FK) |
| Control Plane | ArgoCD execution location — reached transitively via the cluster |

Example:

```text
project01
   │
   ├── application-partition-01
   │      ├── app-001 → CP-1
   │      ├── app-002 → CP-2
   │      └── app-003 → CP-1
   │
   └── application-partition-02
          ├── app-004 → CP-2
          └── app-005 → CP-3
```

If CP-1 fails:

```text
app-001 → CP-2
app-003 → CP-2
```

but:

```text
app-001 ∈ application-partition-01
app-003 ∈ application-partition-01
```

does not change.

This gives stable ApplicationSet identities during control-plane failover.

---

# 13. Routing Service APIs

The APIs consumed by ArgoCD/ApplicationSet should return only the data needed for the current reconciliation level.

The goal is to avoid returning all applications or all clusters in one response.

## 13.0 Protocol — ArgoCD Plugin Generator

All ArgoCD-facing data APIs are exposed via the **ArgoCD ApplicationSet Plugin Generator** protocol, not as conventional REST endpoints.

```http
POST /api/v1/getparams.execute
Content-Type: application/json

{
  "input": {
    "parameters": {
      "resource": "<resource-name>",
      ...
    }
  }
}
```

The `resource` parameter selects the handler. The response is always:

```json
{
  "output": {
    "parameters": [ { ... }, { ... } ]
  }
}
```

Each element in `parameters` becomes one parameter set that ApplicationSet uses to generate one Application.

Supported `resource` values:

| `resource` | Handler | Required extra params |
|---|---|---|
| `cluster-partitions` | All cluster partitions | — |
| `cluster-groups` | Clusters in a partition, grouped by control plane | `partitionNumber` |
| `project-partitions` | All project partitions (includes all CP names for fan-out) | — |
| `project-groups` | Projects in a partition, fanned out per control plane | `partitionNumber` |
| `application-partitions` | All application partitions | — |
| `application-groups` | Applications in a partition, grouped by control plane | `partitionNumber` |

---

## 13.1 `cluster-partitions`

Returns one element per cluster partition. ApplicationSet creates one `cluster-partition-NNN` Application per element.

Example element:

```json
{
  "partitionNumber": 1,
  "generation": 51,
  "clusterCount": 100
}
```

---

## 13.2 `cluster-groups`

Returns one element per control plane that has clusters in the given partition. Each element carries the full cluster list for that CP, including credentials (`config`) for ArgoCD cluster Secret generation.

Example element:

```json
{
  "partitionNumber": 1,
  "controlPlane": "cp-1",
  "clusters": [
    {
      "name": "cluster-001",
      "server": "https://cluster-001.example.internal",
      "config": { "bearerToken": "..." }
    }
  ]
}
```

`config` is the raw `auth` JSONB from the `clusters` table, passed through verbatim. Clusters without a control-plane assignment are excluded.

---

## 13.3 `project-partitions`

Returns one element per project partition. Each element includes all control-plane names so that the downstream ApplicationSet can fan out one Application per control plane (AppProjects must exist on all control planes).

Example element:

```json
{
  "partitionNumber": 1,
  "generation": 42,
  "projectCount": 100,
  "controlPlanes": ["cp-1", "cp-2", "cp-3"]
}
```

---

## 13.4 `project-groups`

Returns one element per control plane for the given partition. Every CP receives the complete project list because AppProjects must exist on all control planes.

Each project carries the list of clusters assigned to it (from `project_clusters`), including the namespace whitelist for each cluster. This data is used by the `project-registration` Helm chart to build `spec.destinations` on AppProjects.

Example element:

```json
{
  "partitionNumber": 1,
  "controlPlane": "cp-1",
  "projects": [
    {
      "name": "payments",
      "clusters": [
        { "name": "cluster-001", "namespaces": ["payments-prod", "payments-staging"] },
        { "name": "cluster-002", "namespaces": [] }
      ]
    },
    {
      "name": "checkout",
      "clusters": []
    }
  ]
}
```

`namespaces: []` means the cluster is registered without a namespace restriction — the AppProject destination for that cluster uses `namespace: '*'`.
`clusters: []` means the project has no assigned clusters — the AppProject falls back to a wildcard destination until clusters are added.

---

## 13.5 `application-partitions`

Returns one element per application partition. ApplicationSet creates one `application-partition-NNN` Application per element.

Example element:

```json
{
  "partitionNumber": 1,
  "generation": 101,
  "applicationCount": 100
}
```

---

## 13.6 `application-groups`

Returns one element per control plane that has applications in the given partition. Each element carries the full application list for that CP.

Example element:

```json
{
  "partitionNumber": 1,
  "controlPlane": "cp-1",
  "applications": [
    {
      "name": "my-app-3f2a1",
      "project": "payments",
      "cluster": "cluster-001",
      "sources": [
        {
          "repoURL": "https://github.com/company/my-app.git",
          "revision": "main",
          "path": "deploy"
        }
      ]
    }
  ]
}
```

`sources` is the raw JSONB array from the `applications` table — any ArgoCD-compatible source shape is valid. Applications without a control-plane assignment (i.e. their cluster has no `control_plane_id`) are excluded.

---

# 14. ApplicationSet Generator decision

We considered using:

```text
Plugin Generator + List Generator
```

to generate N Applications from a numeric value such as:

```json
{
  "partitionCount": 5
}
```

The native List Generator does not provide a simple `count: 5` primitive.

More importantly, we do not need it.

The Plugin Generator can directly return the actual partition descriptors.

For example:

```http
GET /internal/argocd/application-partitions
```

returns:

```json
[
  {
    "partition": "0001"
  },
  {
    "partition": "0002"
  },
  {
    "partition": "0003"
  },
  {
    "partition": "0004"
  },
  {
    "partition": "0005"
  }
]
```

ApplicationSet creates one Application per returned element.

Therefore:

```text
Plugin Generator
       │
       │ returns N partition objects
       ▼
ApplicationSet
       │
       ├── partition-0001
       ├── partition-0002
       ├── partition-0003
       ├── partition-0004
       └── partition-0005
```

**No List Generator is required.**

This is simpler and makes the Routing Service responsible for partition discovery, while ApplicationSet remains responsible for turning those descriptors into Applications.

---

# 15. ApplicationSet hierarchy

There are three major dynamic resource pipelines:

```text
PROJECT PARTITIONS
       │
       ▼
Project Partition ApplicationSet
       │
       ▼
Project Partition Applications
       │
       ▼
Project Partition ApplicationSets
       │
       ▼
Projects


CLUSTER PARTITIONS
       │
       ▼
Cluster Partition ApplicationSet
       │
       ▼
Cluster Partition Applications
       │
       ▼
Cluster Partition ApplicationSets
       │
       ▼
Cluster registration resources


APPLICATION PARTITIONS
       │
       ▼
Application Partition ApplicationSet
       │
       ▼
Application Partition Applications
       │
       ▼
Application Partition ApplicationSets
       │
       ▼
User Applications
```

The important ArgoCD hierarchy rule is:

```text
ApplicationSet → Application
Application → ApplicationSet
ApplicationSet → Application
```

An ApplicationSet does not directly create another ApplicationSet.

The Application created by the first ApplicationSet manages the child ApplicationSet.

---

# 16. Project partition hierarchy

## Level 1 — Project Partition ApplicationSet

Managed ArgoCD has a top-level ApplicationSet:

```text
project-partition-appset
```

It uses a Plugin Generator:

```http
GET /internal/argocd/project-partitions
```

Suppose the response is:

```json
[
  {
    "partition": "01"
  },
  {
    "partition": "02"
  }
]
```

It generates:

```text
project-partition-01
project-partition-02
```

These are Applications in Managed ArgoCD.

---

## Level 2 — Project Partition Application

Example:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: project-partition-01
  namespace: argocd
spec:
  project: platform

  source:
    repoURL: https://github.com/company/argocd-platform.git
    targetRevision: main
    path: charts/project-partition
    helm:
      valuesObject:
        partition: "01"

  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
```

This Application manages the child ApplicationSet:

```text
project-partition-01  (ApplicationSet)
```

---

## Level 3 — Project Partition ApplicationSet

The child ApplicationSet receives:

```text
partition = 01
```

and calls:

```http
GET /internal/argocd/project-partitions/01
```

It receives only the projects in partition 01.

It creates one Application per project.

For example:

```text
project-001
project-002
...
project-100
```

---

# 17. Cluster partition hierarchy

## Level 1 — Cluster Partition ApplicationSet

```text
cluster-partition-appset
```

uses:

```http
GET /internal/argocd/cluster-partitions
```

Example:

```json
[
  {
    "partition": "01"
  },
  {
    "partition": "02"
  },
  {
    "partition": "03"
  }
]
```

It creates:

```text
cluster-partition-01
cluster-partition-02
cluster-partition-03
```

---

## Level 2 — Cluster Partition Application

Example:

```text
cluster-partition-01
```

manages the child ApplicationSet:

```text
cluster-partition-01  (ApplicationSet)
```

and passes:

```text
partition = 01
```

---

## Level 3 — Cluster Partition ApplicationSet

The child ApplicationSet calls:

```http
GET /internal/argocd/cluster-partitions/01
```

It receives only the clusters in partition 01 and creates the corresponding cluster registration resources.

Conceptually:

```text
Managed ArgoCD
      │
      ▼
cluster-partition-01  (Application)
      │
      ▼
cluster-partition-01  (ApplicationSet)
      │
      ▼
Cluster registration resources
```

---

# 18. Application partition hierarchy

## Level 1 — Application Partition ApplicationSet

Managed ArgoCD has:

```text
application-partition-appset
```

It calls:

```http
GET /internal/argocd/application-partitions
```

Example:

```json
[
  {
    "partition": "0001"
  },
  {
    "partition": "0002"
  },
  {
    "partition": "0003"
  }
]
```

It creates:

```text
application-partition-0001
application-partition-0002
application-partition-0003
```

---

## Level 2 — Application Partition Application

Example:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: application-partition-0001
  namespace: argocd
spec:
  project: platform

  source:
    repoURL: https://github.com/company/argocd-platform.git
    targetRevision: main
    path: charts/application-partition
    helm:
      valuesObject:
        partition: "0001"

  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
```

This Application manages the child ApplicationSet:

```text
application-partition-0001  (ApplicationSet)
```

---

## Level 3 — Application Partition ApplicationSet

The child ApplicationSet calls:

```http
GET /internal/argocd/application-partitions/0001
```

It receives only applications assigned to that partition.

It creates one actual Application for each item.

Example:

```text
application-partition-0001  (ApplicationSet)
        │
        ├── app-001
        ├── app-002
        ├── app-003
        └── ...
```

Each generated Application points to the appropriate control-plane ArgoCD instance.

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

The flow is:

```text
Managed ArgoCD
      │
      ▼
application-partition-0001  (Application)
      │
      ▼
application-partition-0001  (ApplicationSet)
      │
      ▼
app-001
      │
      ▼
CP-1 ArgoCD
      │
      ▼
User Cluster
```

---

# 19. Control-plane installation

Control-plane installation remains separate from user-owned project/application state.

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

It creates:

```text
control-plane-cp-1
control-plane-cp-2
control-plane-cp-3
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

# 20. Complete hierarchy

```text
                                  Git
                                   │
                                   ▼
                            Managed ArgoCD
                                   │
             ┌─────────────────────┼──────────────────────┐
             │                     │                      │
             ▼                     ▼                      ▼
      Control-Plane         Project-Partition       Cluster-Partition
      ApplicationSet        ApplicationSet          ApplicationSet
             │                     │                      │
             ▼                     ▼                      ▼
       CP Applications       Partition Apps          Partition Apps
             │                     │                      │
             │                     ▼                      ▼
             │              Project Partition        Cluster Partition
             │              ApplicationSets          ApplicationSets
             │                     │                      │
             │                     ▼                      ▼
             │                  Projects             Registrations
             │
             │
             │
             └──────────────┐
                            ▼
                    Application-Partition
                       ApplicationSet
                            │
                            ▼
                    Application Partition
                       Applications
                            │
                            ▼
                    Application Partition
                       ApplicationSets
                            │
                            ▼
                      User Applications
                            │
                            ▼
                     Control Plane ArgoCD
                            │
                            ▼
                       User Cluster
```

---

# 21. Security model

The security model intentionally avoids duplicating the entire ArgoCD authorization engine inside the Routing Service.

## Routing Service

The Routing Service performs the platform-level authorization check:

```text
Can this user operate on Project X?
```

For example:

```text
Alice → project-a → allowed
Alice → project-b → denied
```

Once authorized, the requested desired state can be persisted.

The Routing Service does not need to duplicate all ArgoCD `AppProject` policy checks.

---

# 22. Control-plane AppProject

The control-plane ArgoCD `AppProject` remains the resource-policy boundary.

It should enforce:

- allowed source repositories
- allowed destinations
- allowed namespaces
- allowed namespace-scoped resources
- allowed cluster-scoped resources

User Applications must not be allowed to:

```text
deploy into argocd namespace
create Application
create ApplicationSet
create AppProject
modify ArgoCD control-plane resources
```

Prefer an allow-list of permitted workload resource types rather than an unrestricted policy with a growing deny list.

## AppProject destinations — generated from database

`spec.destinations` on AppProjects are **not** statically configured; they are generated at reconciliation time from the cluster assignments stored in `project_clusters`.

The `project-groups` API response (§13.4) carries the cluster list for each project. The `project-registration` Helm chart translates this into `spec.destinations` entries:

- Each assigned cluster contributes one or more destination entries.
- If a cluster has a namespace whitelist (`namespaces` column), one entry is emitted per namespace.
- If a cluster has no namespace restriction, a single entry with `namespace: '*'` is emitted.
- If a project has no cluster assignments, a wildcard destination (`server: '*' / namespace: '*'`) is used as a safe placeholder until clusters are added.

This means that adding or removing a cluster from a project in the database is sufficient to update the AppProject destinations — no manual Git edits are required.

---

# 23. Managed ArgoCD identity

When Managed ArgoCD creates an Application or ApplicationSet on a control-plane cluster, the original user identity is not propagated.

The control-plane Kubernetes API sees the identity represented by the cluster credential configured in Managed ArgoCD.

For example:

```text
system:serviceaccount:kube-system:argocd-manager
```

if the registered credential uses that ServiceAccount.

Therefore:

```text
User
  ↓
Routing Service
  ↓
Managed ArgoCD
  ↓
Control-plane Kubernetes API
```

does not become:

```text
Alice → Control-plane Kubernetes API
```

It becomes:

```text
Managed ArgoCD platform identity → Control-plane Kubernetes API
```

This is why user/project authorization is performed by the Routing Service, while the control-plane AppProject provides the final ArgoCD resource-policy boundary.

For local development, broad cluster permissions may be acceptable. For production, the Managed ArgoCD credential should be narrowed to the resources required by the platform.

---

# 24. Preventing privilege escalation

A major security requirement is that a user Application must not be able to create an ArgoCD Application/ApplicationSet that escapes the user's project scope.

For example, this must not be possible:

```text
User Application
      │
      ▼
creates ApplicationSet
      │
      ├── privileged project
      ├── production cluster
      └── unauthorized destination
```

Therefore user AppProjects must prevent user workloads from creating ArgoCD control-plane resources.

The intended trust model is:

```text
Platform-owned resources
        │
        ▼
Managed ArgoCD
        │
        ▼
Control-plane ArgoCD

User workloads
        │
        └── cannot modify the platform/control-plane ArgoCD resources
```

---

# 25. Failover model

Control-plane assignment is stored on the `clusters` table (`control_plane_id`).
Because applications reference their cluster, the control plane is derived transitively —
there is no `control_plane_id` column on the `applications` table.

Example:

```text
cluster-001 → CP-1
cluster-002 → CP-1
cluster-003 → CP-2
```

If CP-1 becomes unavailable, only the cluster records are updated:

```text
cluster-001 → CP-2
cluster-002 → CP-2
cluster-003 → CP-2
```

The Application Partition API resolves each application's control plane by joining
`applications → clusters → control_planes`, and exposes the new destination.

The generated Applications reconcile toward the new control plane.

Application partition membership does not need to change.

Therefore:

```text
Control-plane failure
       │
       ▼
update cluster.control_plane_id
       │
       ▼
Routing API reflects new destination (via cluster join)
       │
       ▼
ApplicationSet reconciliation
       │
       ▼
Application moves to another CP
```

This supports the goal of making control planes replaceable and reducing recovery complexity.

---

# 26. Why partitioning is important at scale

Consider:

```text
1000 clusters
×
100 applications per cluster
=
100,000 applications
```

We do not want Managed ArgoCD to have 100,000 top-level Application objects simply because those applications exist in the platform.

Partitioning limits the number of Applications handled by a single ApplicationSet.

Example:

```text
Application partition target = 100

100,000 applications
       ↓
~1000 application partitions
       ↓
~1000 partition Applications
       ↓
~1000 partition ApplicationSets
       ↓
~100 applications per partition
```

The exact target must be determined through ArgoCD scale testing.

The same principle applies independently to:

```text
projects
clusters
applications
```

---

# 27. Payload-size strategy

The ApplicationSet hierarchy intentionally retrieves data in levels.

## Level 1

Small response:

```http
GET /internal/argocd/application-partitions
```

Returns:

```json
[
  {"partition": "0001"},
  {"partition": "0002"},
  {"partition": "0003"}
]
```

## Level 2

Bounded response:

```http
GET /internal/argocd/application-partitions/0001
```

Returns only the applications in partition 0001.

This prevents a single ApplicationSet reconciliation from repeatedly transferring the entire platform application inventory.

The same model is used for projects and clusters.

---

# 28. Reconciliation latency

The design is intended to support relatively short `requeueAfterSeconds`.

A change to one application should primarily affect:

```text
that application's partition
```

rather than:

```text
all applications in the project
```

A control-plane reassignment should primarily affect:

```text
the affected applications/partitions
```

rather than causing a global rebalance.

Stable partition membership is therefore important for minimizing reconciliation churn.

Generation fields should be included in partition responses so that state changes are observable and can later be used for more efficient change detection.

---

# 29. Important design decisions

## Do not dynamically rebalance partitions

Do not do:

```text
sort resources
→ divide every N
→ recalculate every request
```

Use persistent partition assignment.

## Do not use List Generator for numeric expansion

Do not return:

```json
{
  "partitionCount": 10
}
```

and expect List Generator to automatically create ten elements.

Instead return:

```json
[
  {"partition": "01"},
  {"partition": "02"},
  {"partition": "03"},
  ...
  {"partition": "10"}
]
```

from the Plugin Generator endpoint.

## Do not propagate user identity into control planes

The platform authorization boundary is:

```text
Routing Service → project authorization
```

The ArgoCD policy boundary is:

```text
Control-plane AppProject → resource policy
```

## Do not couple partition to control plane

Partition membership is for scale/reconciliation.

Control-plane assignment is for execution location.

They are independent.

---

# 30. Recommended implementation sequence

```text
1. Managed ArgoCD
        ↓
2. Control-plane Cluster Generator
        ↓
3. Control-plane ArgoCD installation
        ↓
4. Cluster registration
        ↓
5. Cluster partitions
        ↓
6. Project partitions
        ↓
7. Application partitions
        ↓
8. Partition ApplicationSets
        ↓
9. Actual user Applications
        ↓
10. AppProject policy generation
        ↓
11. Control-plane failover/reassignment
        ↓
12. Scale testing
```

The first useful end-to-end implementation should be deliberately small:

```text
1 project
1 project partition
1 application partition
1 control plane
2–5 applications
```

Then validate:

- Plugin Generator behavior
- partition discovery
- partition API payload size
- ApplicationSet reconciliation
- Application creation
- control-plane routing
- AppProject policy enforcement
- application deletion without rebatching
- control-plane reassignment without partition movement

After that, scale progressively to:

```text
100 applications
1,000 applications
10,000 applications
100,000 applications
```

and use measurements to choose:

- partition target size
- `requeueAfterSeconds`
- Managed ArgoCD resource limits
- ApplicationSet controller capacity
- Routing Service API capacity
- PostgreSQL query/index strategy

---

# 31. API behavior rules

## Unique names

| Resource | Uniqueness scope | Enforcement |
|---|---|---|
| `clusters` | Global (`clusters.name` DB unique constraint) | Service-level pre-check → HTTP 409 on duplicate; DB constraint as safety net |
| `projects` | Global (`projects.name` DB unique constraint) | Service-level pre-check → HTTP 409 on duplicate; DB constraint as safety net |
| `applications` | Per project (`(project_id, name)` unique constraint) | 5-char hex suffix appended on creation (e.g. `my-app` → `my-app-3f2a1`); suffix space (~1 M combinations) eliminates the need for a pre-check |

The suffixed application name is returned in the create response and is the stable identity used in ArgoCD. Callers must store it — the original base name alone is not a valid lookup key.

## Immutable fields

The following fields cannot be changed after creation via any API call:

| Resource | Immutable fields |
|---|---|
| Cluster | `name`, `cluster_partition_id` |
| Project | `name`, `project_partition_id`, `created_by` |
| Application | `name`, `project_id`, `application_partition_id` |

Update endpoints silently ignore these fields if they are included in the request body.

## Cluster must be in project

An application's target cluster must be a member of the application's project (`project_clusters` row must exist). The service validates this on creation and rejects the request with HTTP 400 if the cluster is not assigned to the project.

## Application cluster update

On update, an application's `cluster_id` may be changed, but only to a cluster that is already assigned to the same project.

---

# 32. TODO / Future Enhancements

This section tracks known gaps and deferred work items. Implementation details are planned separately per item.

---

## 32.1 Identity and Authentication

### Multi-source external identity providers
The platform has no authentication layer. Users are identified by UUID only.

**Needed:** Support pluggable external identity providers — LDAP, Azure AD, Google OAuth, GitHub OAuth. Multiple providers must be usable simultaneously so different teams can authenticate via different corporate directories.

### Project authorization via AD groups
Project membership is currently individual user-to-project only. This does not scale for teams managed via Active Directory or LDAP groups.

**Needed:** Projects must be authorizable via AD/LDAP groups in addition to individual users. A user is authorized for a project if they are a direct member or if any of their group memberships grants access.

---

## 32.2 User and Resource Management APIs

### User management API
The `users` table exists but no API is exposed to manage it.

**Needed:** Full CRUD API for users — create, list, update, deactivate.

### Resource status lifecycle
Resource `status` fields (`UNKNOWN`, `ACTIVE`, `ERROR`) are never updated after creation. They do not reflect actual ArgoCD state.

**Needed:** Resource status must be driven by live ArgoCD sync and health state. When an application syncs successfully, a cluster connects, or a project is created in ArgoCD, the corresponding platform record's `status` must be updated automatically. See §32.11.

---

## 32.3 ApplicationSet Behaviour

### Configurable poll interval per resource type
All ApplicationSets share a single hardcoded poll interval. Different resource types change at very different frequencies.

**Needed:** The reconciliation poll interval (`requeueAfterSeconds`) must be independently configurable per resource type — control-planes, cluster-partitions, project-partitions, application-partitions — without code changes.

### Safe pruning policy for the control-plane ApplicationSet
Automatic pruning on the control-plane ApplicationSet would cascade ArgoCD removal to all child workloads on user clusters — a catastrophic blast radius.

**Needed:** The control-plane ApplicationSet must not auto-prune. Removing a control plane from the registry must require a deliberate two-step operation (drain then remove), not an automatic response to a Plugin Generator response change. A decommissioning runbook must be defined.

### On-demand reconciliation API
A desired-state change currently takes up to one full poll cycle to propagate. There is no way to force reconciliation of a specific partition or resource.

**Needed:** An API on the platform service to trigger immediate reconciliation of a specific partition or resource without waiting for the next poll cycle. The platform service owns the ArgoCD connection; the caller does not interact with ArgoCD directly.

### Event-driven reconciliation
The platform service should not rely on ArgoCD polling as the primary mechanism for propagating state changes.

**Needed:** After any state-mutating write (application registered, cluster reassigned, etc.), the platform service must automatically trigger a reconciliation of only the affected partition — not wait for the next poll cycle. The platform service must be connected to Managed ArgoCD for this purpose. Polling remains as a fallback safety net only.

---

## 32.4 Partition Lifecycle

### Empty partition cleanup
When all resources leave a partition, the partition row remains indefinitely with zero members.

**Needed:** Empty partitions must be automatically reclaimed after a configurable inactivity period. Cleanup must not trigger rebalancing of resources in other partitions.

---

## 32.5 Management UI

### UI is not production-ready
The current UI is a prototype with no authentication, no pagination, and limited error handling.

**Needed before production use:**
- Authentication via SSO / OAuth
- Pagination for large resource lists
- Cluster-to-project assignment flow
- Live application status and health driven by ArgoCD state
- Audit log and history view per resource

---

## 32.6 Observability and Operations

### Custom metrics
The service has no platform-level metrics beyond standard health endpoints.

**Needed:** Metrics covering partition fill rates, application and cluster counts per control plane, Plugin Generator request latency, and desired-state churn rate.

### Control-plane failover runbook
There is no documented procedure for executing a failover safely under incident conditions.

**Needed:** A runbook covering how to drain a control plane, reassign its clusters, validate ApplicationSet reconciliation, and confirm application health on the new control plane. This is superseded by §32.10 once the failover API is available, but a manual runbook is required in the interim.

---

## 32.7 Cluster Authentication

### Typed and validated auth mechanisms
The cluster auth field accepts any shape without validation. Different cluster types require different authentication mechanisms (bearer token, TLS client certificate, AWS EKS exec credential, GKE Workload Identity, in-cluster).

**Needed:** Cluster registration must accept a typed auth mechanism field and validate that the provided credentials match the expected shape for that type. Malformed credentials must be rejected at registration time, not silently passed through to ArgoCD.

---

## 32.8 External Secrets Management

### Credentials stored in the database
Cluster credentials (API server tokens, TLS keys) and any ArgoCD API tokens are currently stored directly in PostgreSQL. This creates backup exposure, rotation complexity, and policy violations.

**Needed:** Sensitive credentials must not be stored at rest in the database. The platform must integrate with an external secrets backend. Credentials must be rotatable without a platform API call or database update.

---

## 32.9 Partition-Level Response Caching

### Plugin Generator responses re-queried on every poll cycle
At steady state, most Plugin Generator calls return unchanged data. At scale this creates unnecessary database load across thousands of partitions.

**Needed:** Plugin Generator responses must be cached at the partition level. The cache must be invalidated explicitly whenever partition state changes — application registered or deleted, cluster reassigned, cluster failover, project cluster assignment changed. Stale data must not be served; a TTL is a safety net only, not the primary consistency mechanism. Caching must complement event-driven reconciliation (§32.3): a write invalidates the cache, ArgoCD is refreshed, and the next Plugin Generator call gets fresh data from the database and repopulates the cache.

---

## 32.10 Controlled Failover API

### No API for failover — only raw database updates
Failover today requires direct database writes with no progress tracking, no safety checks, and no atomicity guarantee across large cluster sets.

**Needed:** A single API that encapsulates the complete failover operation with the following capabilities:

- **Full control-plane failover** — move all clusters off a control plane to a target control plane.
- **Partial failover** — move a specific subset of clusters, selected by cluster name or by label filters.
- **Stepping** — move all clusters at once (single switch) or in configurable batches. In batched mode, the API waits for each batch to reconcile successfully in ArgoCD before starting the next batch.
- **Dry run** — return the planned cluster list and batch schedule without making any changes.
- **Async with progress tracking** — the API returns immediately with an operation ID; callers poll for progress, batch completion, and any paused or failed state.
- **Cancel** — stop after the current batch completes without rolling back already-moved clusters.

---

## 32.11 ArgoCD Notification Integration

### ArgoCD state changes are not reflected in the platform database

Resource status in the platform database is never updated after creation. Users have no way to receive notifications when their applications deploy, clusters connect, or projects are provisioned.

**Needed — two capabilities:**

**1. Inbound: ArgoCD → Platform status updates**

The platform must receive state change events from ArgoCD and update the corresponding resource `status` in the database. The following events must be handled:

| Event | Trigger |
|---|---|
| Application synced | ArgoCD sync succeeded |
| Application deployed | ArgoCD sync succeeded and health is Healthy |
| Application degraded | ArgoCD health degraded |
| Application sync failed | ArgoCD sync failed |
| Cluster connected | Cluster successfully connects to its control plane |
| Cluster disconnected | Cluster becomes unreachable from its control plane |
| Project created | AppProject successfully created in ArgoCD |
| Project updated | AppProject updated in ArgoCD |

**2. Outbound: User notifications delegated to ArgoCD's notification system**

User-facing notifications (webhooks, Slack, email, PagerDuty, etc.) are handled natively by the ArgoCD notifications controller running on each control plane — not by the platform service. The platform service must not duplicate this responsibility.

The `application-registration` Helm chart must be updated to accept notification subscription configuration as values and render the corresponding ArgoCD notification annotations on the generated Application resource. This allows users to declare their notification preferences as part of application registration, and ArgoCD delivers the notifications directly.

The Helm chart must support any notification channel and trigger combination that ArgoCD supports — the chart should not restrict or enumerate specific channels. The set of available notification channels and templates is an ArgoCD configuration concern on each control plane, not a platform service concern.
