# Speaker Notes — Scalable Managed ArgoCD Platform

> **How to use:** Each section maps to one scene. Within a scene, each step is a separate click.
> Read the note for that step *after* clicking, while the animation plays.
> Pause naturally between steps — the animation tells part of the story.

---

## Scene 1 — Intro

**[Enter scene — ArgoCD icon pulses in]**
> *(Let the icon settle for 2–3 seconds. No need to speak immediately — the visual does the work.)*

**[Click → Title appears: "Scalable Managed ArgoCD"]**
> "Today I want to walk you through a platform we've been building — Scalable Managed ArgoCD."

**[Click → Tagline appears: "GitOps built for scale"]**
> "At its core, it's a GitOps platform — but designed to operate at a scale that wasn't possible with ArgoCD out of the box. Let me show you what we mean."

---

## Scene 2 — The Opportunity

**[Enter scene — "1000s of Kubernetes clusters" appears]**
> "Let me start with the landscape. Across the organization, we're running thousands of Kubernetes clusters. And almost every workload — whether it's a microservice, a data pipeline, or a managed offering — is running on one of these clusters. That's the scale we're operating at."

> "And right now, each team is doing something different to manage their deployments. Their own scripts, their own pipelines, their own runbooks. There's no single standard."

**[Click → Individual Cluster Owners card]**
> "The first group of teams are what I'd call Individual Cluster Owners. These are teams that own their own clusters — one cluster, maybe a few for multi-region — and deploy their workloads directly onto them. Think of service teams, infra teams. They have full ownership."

**[Click → Managed Service Platform Teams card]**
> "The second group is more interesting. These are platform teams that operate clusters not for themselves, but to offer services to other teams. They're running controllers, provisioners, lifecycle tooling — essentially, Kubernetes-as-a-service. And they have to manage all of that orchestration somehow."

**[Click → Service Tenants card]**
> "And then there's the third group — the consumers of those platform teams. These are teams that don't own a cluster at all. They've been given a namespace, a slice of compute, and they need to deploy their workloads into it."

**[Click → Coverage bars fill across all three]**
> "Here's where we are today. We're fully serving the first group — teams with their own clusters. The second and third groups — platform teams and their tenants — we're only partially or minimally reaching them. And that's where the opportunity is."

**[Click → Closing statement]**
> "If we can bring all three of these groups onto a single GitOps platform, no team needs to build or maintain their own deployment tooling. One standard. One place to look. That's the vision."

---

## Scene 3 — The Scaling Problem

**[Enter scene]**
> "So why hasn't this been done already? What's the blocker? Let's talk about what happens when you try to scale ArgoCD."

*(Walk through the scaling steps as they appear — memory climbing, reconciliation slowing, OOM events.)*

**[As memory/load builds]**
> "A single ArgoCD instance handles everything — every application, every cluster. It works fine at first. But as more teams onboard, as more applications are registered, the ApplicationSet controller starts to struggle."

**[As OOM/failure state approaches]**
> "It's not a bug. It's physics. The controller has to reconcile everything on every poll cycle. As the response payload grows, it eventually hits memory limits and gets killed."

**[Final narrative: "One ArgoCD. Everything depends on it."]**
> "One ArgoCD. Everything depends on it. And that's the architectural ceiling we needed to break through."

> *"What happens when everything keeps growing?"* — and the answer is: the platform stops working.

---

## Scene 4 — Inside ArgoCD

**[Enter scene — ArgoCD internals appear]**
> "Before we talk about the solution, let's be precise about what's happening inside ArgoCD. Because understanding the internals is what led us to the right architectural decisions."

**[As components appear]**
> "ArgoCD has several components: an API Server that's the entry point for all requests, a Redis cache for shared state, a Repo Server that fetches and renders Git manifests, and the Application Controller — the reconciliation engine — which is the piece that actually does the heavy lifting."

**[Step 2 — More Git repos appear]**
> "As more teams onboard, the number of Git repositories and applications the controller needs to track multiplies."

**[Step 5 — Sharding appears]**
> "ArgoCD does support sharding — splitting the Application Controller into multiple shards. But sharding has limits too. And more importantly, it doesn't solve the fundamental problem: everything still runs in one ArgoCD instance, sharing one Redis, one API Server, one failure domain."

> "What we need isn't sharding. We need isolation."

---

## Scene 5 — Multiple Control Planes

**[Enter scene — Single ArgoCD shown]**
> "The idea is straightforward: instead of one ArgoCD managing everything, we run multiple ArgoCD instances — each one a Control Plane."

**[Step 2 — Three CPs appear]**
> "Each Control Plane manages a bounded slice of the workload. CP-1 sees its applications. CP-2 sees its applications. They don't share memory, they don't share failure domains. They're independent."

**[Step 3 — User node appears]**
> "But now we have a new problem. If there are three control planes, which one does the user connect to? Which one do they register their cluster with?"

**[Step 4 — Platform Service appears]**
> "That's where the Managed ArgoCD Platform Service comes in. It's the single entry point. Users and teams interact with one API. The Platform Service handles routing — it knows which control plane owns which cluster, which partition, which application."

**[Final narrative]**
> "One entry point. Smart routing. Each control plane sees only its slice. This is the core architectural shift."

---

## Scene 6 — Plugin Generator

**[Enter scene]**
> "Now, how does ArgoCD on each Control Plane know what applications to create? The answer is the ApplicationSet Plugin Generator."

**[Step 1 — Poll animation]**
> "ArgoCD's ApplicationSet polls your API server on a regular interval. It says: 'give me the parameters I need to generate applications.'"

**[Step 2 — Parameter sets returned]**
> "Your API returns a list of parameter sets — one entry per application that should exist. It's completely dynamic. The API is your source of truth."

**[Step 3 — Applications created]**
> "For each entry in that list, ArgoCD creates one Application object. The mapping is one-to-one. Clean and predictable."

**[Step 4 — Apps reconcile]**
> "Each Application then reconciles to its destination cluster. If the cluster has drifted from the desired state, ArgoCD brings it back in line. Automatically."

**[Step 5 — API changes]**
> "And when the data in your API changes — a new application, a deleted one, a configuration update — ArgoCD picks it up on the next poll and adapts."

**[Final narrative]**
> "Your API is the source of truth. ArgoCD is the executor. That separation is what gives us flexibility."

---

## Scene 7 — Platform Architecture

**[Enter scene]**
> "Let me show you the full picture — how all the pieces connect."

**[Step 1 — PostgreSQL appears]**
> "At the top is PostgreSQL. This is where all platform state lives — every cluster that's been onboarded, every project, every application. PostgreSQL is the authoritative source of truth for the platform."

**[Step 2 — ApplicationSets appear]**
> "Inside Managed ArgoCD, we have ApplicationSets — one per partition type. Each one is configured with the Plugin Generator, pointing at the Platform Service."

**[Step 3 — Platform Service polling]**
> "The Platform Service is a Spring Boot application that implements the Plugin Generator API. When an ApplicationSet polls it, it queries PostgreSQL, builds the parameter list, and returns it. ArgoCD takes that list and generates Applications."

**[Step 4 — Apps appear on CPs]**
> "Those Applications are created on the appropriate Control Planes. Each Control Plane gets only the applications that belong to its slice."

**[Step 5 — CPs reconcile to clusters]**
> "The Control Planes then reconcile — deploying workloads to the actual destination Kubernetes clusters."

**[Final narrative]**
> "Platform Service is the source of truth. Managed ArgoCD orchestrates. Control Planes execute. Three clear layers, three clear responsibilities."

---

## Scene 8 — Git-Driven Infrastructure

**[Enter scene — Git panel visible]**
> "Now let's talk about how the platform itself is managed. The control planes, the ApplicationSet definitions, the Helm values — all of it lives in Git. Nothing is provisioned manually."

**[Step 1 — cp-3/default.yaml appears with NEW badge, git push shows]**
> "Say we need to add a new control plane to the pool. An admin creates a new values file in the Git repository — `cp-3/default.yaml` — and pushes. That's it. No kubectl, no manual Helm install, no API calls."

**[Step 2 — Cluster Generator detects]**
> "The control-planes ApplicationSet uses a Cluster Generator with a label selector. When a new cluster matching that label appears — which it will, because the Helm chart registers it — the ApplicationSet detects it automatically."

**[Step 3 — Application created]**
> "An Application object is created: `control-plane-cp-3`. This Application points to the Helm chart that installs ArgoCD and registers the new instance."

**[Step 4 — Helm deploys, CP-3 appears]**
> "The Helm chart runs, ArgoCD is installed on CP-3, and it self-registers with the pool. The control plane is live."

**[Step 5 — All 3 CPs online]**
> "Three control planes, all provisioned and managed from the same Git repository. The ApplicationSet definitions, the Helm values, the cluster registration — it's all declarative and version-controlled."

**[Final narrative]**
> "One Git push. A new control plane joins the pool. No manual steps. No runbooks. Just Git."

---

## Scene 9 — Cluster & App Lifecycle

**[Enter scene — Platform Service visible]**
> "Let's follow a real workflow — onboarding a cluster and creating an application."

**[Step 1 — POST /clusters with parameters]**
> "A team calls `POST /clusters` on the Platform Service API. They provide parameters — region, expected load, availability zone, whatever is relevant to their use case. The platform takes it from there."

**[Step 2 — Assignment box evaluates]**
> "The assignment algorithm evaluates the current state of the control plane pool. It looks at the parameters the team provided — region affinity, current load on each CP, capacity — and picks the best match."

**[Step 3 — CL-1 → CP-2 assigned]**
> "In this case, CL-1 is assigned to CP-2. That assignment is persisted in the database. The cluster now has a stable home."

**[Step 4 — POST /applications]**
> "Now the team calls `POST /applications`, referencing their cluster. The platform knows which cluster it's on. It knows which control plane owns that cluster."

**[Step 5 — Generation bump, AppSet reconciles]**
> "The platform bumps the generation counter for that partition. The ApplicationSet picks up the change on its next poll, reconciles, and creates the Application on CP-2."

**[Step 6 — APP-2 deployed]**
> "The application deploys to the cluster. And here's the key insight: the team didn't have to know or care which control plane their application landed on. The platform made that decision based on the data."

**[Final narrative]**
> "Cluster assignment is intelligent. Applications follow their cluster. The team just makes an API call."

---

## Scene 10 — The Growing Pain

**[Enter scene — Single ApplicationSet shown]**
> "With the architecture in place, teams start onboarding. And this is where we hit the next scaling wall."

*(Let the growth animation play — watch the app count and response size climb.)*

**[As response size grows]**
> "Every ApplicationSet poll fetches the full list of applications from Platform Service. As more teams onboard, that list gets longer. The response payload grows with every new application."

**[As OOM state approaches]**
> "At some point — and it happens faster than you'd expect — the payload hits a size that the ApplicationSet controller can't handle. It runs out of memory and gets killed."

**[Final OOM state / narrative]**
> "This is the same problem we had before, just pushed one layer deeper. One ApplicationSet. Every app. Every cluster. As the platform grows — the poll fails."

> "We needed a way to partition the load."

---

## Scene 11 — Partition the Load

**[Enter scene — Controller is down]**
> "The solution is to stop using one ApplicationSet for everything and split the load across multiple ApplicationSets — each one responsible for a partition."

**[Step 1 — Recovery]**
> "The system recovers. But we don't just restart the same broken setup."

**[Step 2 — Split announced]**
> "We partition. Instead of one ApplicationSet querying for all applications across all clusters, we create multiple ApplicationSets — each one querying only for its slice of data."

**[Steps 3–4 — Apps redistribute, partitions poll independently]**
> "Each partition polls independently. They don't know about each other. Each one fetches a much smaller payload on each cycle."

**[Step 5 — Response size comparison]**
> "Before: one ApplicationSet fetching ~512 KB on every poll. After: each partition fetching ~170 KB. And as the platform grows, we add more partitions — the per-partition size stays bounded."

**[Final narrative]**
> "Partition the load. Each ApplicationSet polls for less. The platform scales horizontally — not by making a single component bigger, but by dividing the work."

---

## Scene 12 — The Self-Assembling Hierarchy

**[Enter scene]**
> "Now here's something elegant about this architecture. The partitioning itself is managed by ArgoCD — using a hierarchy of ApplicationSets."

*(Walk through the L1 → L2 → L3 → L4 levels as they appear.)*

**[L1 appears]**
> "At the top is a Level 1 ApplicationSet — a root AppSet. It's responsible for generating the partition structure."

**[L2 — Partition apps appear]**
> "The L1 AppSet creates Application objects — one per partition. These aren't user applications. They're partition applications — metadata objects that represent a partition of the workload."

**[L3 — Partition AppSets appear]**
> "Each partition Application in turn creates a Level 3 ApplicationSet. This is the AppSet that actually polls Platform Service for the applications in its partition."

**[L4 — Actual applications appear]**
> "And those L3 AppSets generate the actual user Applications — the things that deploy workloads to clusters."

> "The hierarchy assembles itself. You add a new partition by updating a parameter in Git. The AppSets cascade through the levels and create everything that needs to exist."

> "L1 controls L2. L2 creates L3. L3 generates L4. Each level is managed by the level above it. It's GitOps all the way down."

---

## Scene 13 — Event-Driven Regeneration

**[Enter scene — Steady state showing]**
> "With partitions in place, let's talk about how we keep database load under control. Because even with partitioned polling, if every AppSet is hitting PostgreSQL on every cycle — that's still a lot of queries."

**[Step 0 — Cache HIT state, slow particles]**
> "In steady state, Platform Service sits in front of PostgreSQL with a Redis cache. The ApplicationSets poll Platform Service every 10 seconds at the L1 level, and every 5 minutes at the partition level. Most of the time, the data hasn't changed — so Platform Service returns the cached response. PostgreSQL isn't touched."

**[Step 1 — Write to PostgreSQL, generation bump]**
> "Now something changes. A team registers a new application. Platform Service writes to PostgreSQL. It also increments the generation counter for partition-002. Generation goes from 41 to 42."

**[Step 2 — Cache eviction]**
> "A `PartitionChangedEvent` fires internally. Platform Service evicts the cache entry for partition-002. Partition-001 and partition-003 are untouched — their cache entries are still valid."

**[Step 3 — Cache MISS, PostgreSQL queried]**
> "On the next poll from the L1 AppSet, Platform Service checks Redis for partition-002 — and gets a MISS. It queries PostgreSQL, gets fresh data, and repopulates the cache."

**[Step 4 — Only partition-002 reconciles]**
> "The L1 AppSet sees that partition-002's generation changed. Only the AppSet for partition-002 triggers reconciliation. It generates the new application on the control plane."

**[Step 5 — Others untouched]**
> "Partition-001 and partition-003 see no generation change. Their AppSets don't reconcile. Zero database queries. Zero unnecessary work."

> "This is the key insight: the generation number is a signal. When it doesn't change, nothing happens. When it does change, only the affected partition reacts."

---

## Scene 14 — Failover

**[Enter scene — CP-1 and CP-2 both healthy]**
> "The last scenario I want to show is failover. What happens when a control plane goes down?"

**[Step 1 — CP-1 degrading]**
> "CP-1 starts showing signs of trouble. Applications are reporting sync errors. The control plane is under stress."

**[Step 2 — CP-1 offline]**
> "CP-1 goes offline. Applications on CP-1 enter Unknown state. The clusters it was managing — UCL-A and UCL-B — are now without a control plane."

**[Step 3 — Failover initiated]**
> "An operator — or an automated system — updates a single field in the database: `cluster.control_plane_id` for UCL-A and UCL-B is changed to point at CP-2."

> "That's it. One database update. No ArgoCD YAML changes. No manual sync operations."

**[Step 4 — AppSets reconcile, apps migrate]**
> "The ApplicationSets detect the change on the next poll. They reconcile. The Applications for UCL-A and UCL-B are now generated on CP-2. CP-2 begins managing those clusters."

**[Step 5 — CP-2 manages all four]**
> "CP-2 now manages all four clusters — its original two plus the two that migrated from CP-1. Zero manual intervention required."

**[Final narrative]**
> "One database update. Full failover. No ArgoCD YAML. No manual sync. Just data."

> "The platform's resilience comes from the data model — not from complex orchestration logic."

---

## Scene 15 — Summary

**[Enter scene — static, everything visible]**
> "Let me bring it all together."

> "What we've built is a scalable, Git-driven GitOps platform built on top of ArgoCD. Five architectural pillars hold it together:"

> "**Git-Driven** — every control plane, every ApplicationSet definition, every Helm value lives in Git. The infrastructure manages itself."

> "**Partitioned and Scalable** — workloads are distributed across a pool of control planes. No single ArgoCD instance is a bottleneck. Add a partition, add a CP — the platform grows horizontally."

> "**Event-Driven** — Redis cache and generation-based invalidation mean we're not hammering the database on every poll cycle. Only what changed gets reconciled."

> "**Intelligent Placement** — clusters aren't randomly assigned to control planes. The assignment uses configurable parameters — region, load, capacity — whatever matters for the workload."

> "**Fault-Tolerant** — a control plane failure is resolved by a data change, not by manual ops. The system recovers automatically."

> "And this platform doesn't serve just one kind of team. It serves individual cluster owners. It serves managed service platform teams. And it serves the tenants of those platforms."

> "One GitOps platform. Thousands of clusters. No team needs to build their own deployment tooling."

*(Pause.)*

> "Happy to take any questions — or we can jump straight into the demo."

---

## General Presentation Tips

- **Pause after narrative cards.** The white text on dark background is meant to be read. Give the audience 3–4 seconds before speaking.
- **Don't read the step labels.** The on-screen text summarizes the step — your job is to add context and emphasis, not repeat what's already shown.
- **Let animations breathe.** Especially for particle flows (Scenes 6, 7, 13) — the motion communicates the data flow visually. Silence for 2–3 seconds is fine.
- **Scene 2 coverage bars (Step 4)** — pause here. The visual comparison of the three bars (full vs. half vs. near-empty) is a strong moment. Let it land before speaking.
- **Scene 13 is the most technical.** If the audience is non-technical, simplify to: "When data changes, only the affected partition reacts. Everything else stays cached. It's efficient by design."
- **Scene 14 (Failover)** — emphasize the "one database update" line. It's counterintuitive that something so simple drives the entire failover. That's the point.
