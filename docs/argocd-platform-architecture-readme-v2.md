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
| `name` | Cluster name |
| `server` | Kubernetes API endpoint/reference |
| `status` | Lifecycle state |
| `cluster_partition_id` | Assigned cluster partition |
| `control_plane_id` | Current control-plane assignment |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

`control_plane_id` is mutable so clusters can be moved between control planes during failover/rebalancing.

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
| `name` | Application name |
| `project_id` | Owning project |
| `cluster_id` | Target user cluster |
| `application_partition_id` | Stable application partition |
| `status` | Lifecycle state |
| `generation` | Desired-state generation |
| `created_at` | Creation timestamp |
| `updated_at` | Last update |

> **Design decision:** `control_plane_id` is intentionally absent from the `applications` table.
> The control plane is derived transitively via `application → cluster → control_plane`.
> Moving an application to a different control plane is achieved by updating `cluster.control_plane_id`,
> which propagates automatically — no redundant FK on each application record is needed.

Application partition assignment is independent of the cluster's control-plane assignment.

An application's partition does not change when its cluster is reassigned to a different control plane.

---

## 4.7 `application_sources`

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

Supports applications with multiple Git/Helm sources.

---

## 4.8 `control_planes`

| Column | Purpose |
|---|---|
| `id` | Control-plane ID |
| `name` | Control-plane name |
| `server` | ArgoCD/Kubernetes endpoint/reference |
| `status` | Healthy/unhealthy/draining/etc. |
| `capacity` | Optional scheduling capacity |
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

---

## 13.1 Project partition discovery

```http
GET /internal/argocd/project-partitions
```

Purpose:

Used by the top-level Project Partition ApplicationSet.

Example response:

```json
[
  {
    "partition": "01",
    "projectCount": 100,
    "generation": 42
  },
  {
    "partition": "02",
    "projectCount": 83,
    "generation": 31
  }
]
```

This response contains partition metadata, not all projects.

---

## 13.2 Project partition data

```http
GET /internal/argocd/project-partitions/{partition}
```

Example:

```http
GET /internal/argocd/project-partitions/01
```

Example response:

```json
{
  "partition": "01",
  "generation": 42,
  "projects": [
    {
      "id": "project-001",
      "name": "payments"
    },
    {
      "id": "project-002",
      "name": "checkout"
    }
  ]
}
```

The response contains only projects in that partition.

---

## 13.3 Cluster partition discovery

```http
GET /internal/argocd/cluster-partitions
```

Example response:

```json
[
  {
    "partition": "01",
    "clusterCount": 100,
    "generation": 51
  },
  {
    "partition": "02",
    "clusterCount": 100,
    "generation": 52
  },
  {
    "partition": "03",
    "clusterCount": 100,
    "generation": 53
  }
]
```

For 1,000 clusters and a target size of 100, this gives approximately 10 cluster partitions.

---

## 13.4 Cluster partition data

```http
GET /internal/argocd/cluster-partitions/{partition}
```

Example:

```http
GET /internal/argocd/cluster-partitions/01
```

Example response:

```json
{
  "partition": "01",
  "generation": 51,
  "clusters": [
    {
      "name": "cluster-001",
      "server": "https://cluster-001.example.internal"
    },
    {
      "name": "cluster-002",
      "server": "https://cluster-002.example.internal"
    }
  ]
}
```

Production cluster credentials should preferably be injected through an External Secrets/secret-store mechanism rather than returned as normal API data.

Local development can use generated cluster Secret manifests as previously planned.

---

## 13.5 Application partition discovery

```http
GET /internal/argocd/application-partitions
```

Example response:

```json
[
  {
    "partition": "0001",
    "applicationCount": 100,
    "generation": 101
  },
  {
    "partition": "0002",
    "applicationCount": 100,
    "generation": 102
  },
  {
    "partition": "0003",
    "applicationCount": 74,
    "generation": 88
  }
]
```

This is intentionally independent of projects.

---

## 13.6 Application partition data

```http
GET /internal/argocd/application-partitions/{partition}
```

Example:

```http
GET /internal/argocd/application-partitions/0001
```

Example response:

```json
{
  "partition": "0001",
  "generation": 101,
  "applications": [
    {
      "name": "app-001",
      "project": "project01",
      "cluster": "cluster-001",
      "controlPlane": "cp-1",
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
      "project": "project01",
      "cluster": "cluster-007",
      "controlPlane": "cp-2",
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

Only the applications in that partition are returned.

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

This Application manages:

```text
project-partition-01-appset
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

manages:

```text
cluster-partition-01-appset
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
cluster-partition-01
      │
      ▼
cluster-partition-01-appset
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

This Application manages:

```text
application-partition-0001-appset
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
application-partition-0001-appset
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
application-partition-0001
      │
      ▼
application-partition-0001-appset
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
