# Why ArgoCD was selected as the GitOps engine

## Objective

Our platform requires a Kubernetes deployment engine that supports
GitOps, continuous reconciliation, multi-cluster deployments,
enterprise-grade access control, automation through APIs, and
operational visibility. The deployment engine should integrate with our
Control Plane while remaining scalable, extensible, and easy to operate.

The purpose of this evaluation is to compare the major open-source
Kubernetes deployment solutions and justify the selection of ArgoCD.

## Open Source Kubernetes Deployment and Management Tools

There are many open-source tools available for Kubernetes application
deployment and management. However, they solve different problems and
should not all be considered direct competitors.

### GitOps Platforms

-   ArgoCD
-   FluxCD
-   Rancher Fleet
-   Kluctl

### Continuous Delivery Platforms

-   Spinnaker
-   Jenkins X

### Deployment Tools

-   Helm
-   Helmfile
-   Kustomize
-   Werf

### Developer Workflow Tools

-   Skaffold

### Platform Solutions

-   Devtron (internally uses ArgoCD)
-   Rancher

### Image Automation

-   Keel

Among these, only a small subset provides true GitOps continuous
reconciliation.

## Tools Supporting Continuous Reconciliation

Continuous reconciliation is the core principle of GitOps. A
reconciliation engine continuously compares the desired state stored in
Git with the actual state running in Kubernetes. Whenever a drift is
detected, it automatically restores the cluster back to the desired
state.

The following open-source tools support continuous reconciliation:

-   ArgoCD
-   FluxCD
-   Rancher Fleet
-   Kluctl

Other tools such as Helm, Helmfile, Spinnaker, Jenkins X, Skaffold, and
Keel perform deployments when triggered but do not continuously
reconcile cluster state.

## Evaluation Criteria

-   Continuous reconciliation
-   Drift detection
-   Self-healing
-   Multi-cluster deployments
-   Fleet management
-   Enterprise RBAC
-   Application lifecycle management
-   REST/gRPC APIs
-   User Interface
-   Scalability
-   Community adoption
-   Long-term sustainability

## Comparison

Capability                  ArgoCD          FluxCD            Rancher Fleet
  --------------------------- --------------- ----------------- ---------------
Continuous reconciliation   Yes             Yes               Yes
Self-healing                Yes             Yes               Yes
Drift detection             Yes             Yes               Yes
Multi-cluster support       Excellent       Excellent         Excellent
Fleet management            Excellent       Decentralized     Good
UI                          Rich            None built-in     Basic
REST/gRPC APIs              Comprehensive   Limited           Limited
RBAC                        Project-based   Kubernetes RBAC   Rancher RBAC
CNCF                        Graduated       Graduated         No

## Why ArgoCD

Although multiple GitOps solutions support reconciliation, ArgoCD
provides the best combination of scalability, operational visibility,
enterprise features, and integration capabilities required for our
platform.

### Continuous Reconciliation

ArgoCD continuously monitors the desired state in Git and the live state
in Kubernetes. Whenever differences are detected, it can detect
configuration drift, report OutOfSync resources, automatically
synchronize changes, restore manually modified resources, and prune
deleted resources. This ensures Kubernetes clusters always converge
toward the desired Git state.

FluxCD and Rancher Fleet also support reconciliation, but ArgoCD
provides richer operational visibility, better APIs, and a stronger
application-centric model.

### Fleet Management

FluxCD follows a decentralized architecture where every cluster runs its
own independent controllers. Each cluster reconciles independently and
there is no centralized application inventory or operational view.

Rancher Fleet provides centralized fleet management but is primarily
optimized for the Rancher ecosystem.

ArgoCD provides a centralized control plane capable of registering
multiple Kubernetes clusters, deploying applications across them,
monitoring health, tracking deployment history, and providing a unified
operational dashboard. This aligns well with our Control Plane
architecture.

### ApplicationSet

ApplicationSet is one of ArgoCD's strongest differentiators. It
dynamically generates Application resources using:

-   List Generator
-   Cluster Generator
-   Git Generator
-   Matrix Generator
-   Merge Generator
-   Pull Request Generator
-   SCM Provider Generator
-   Plugin Generator

ApplicationSet enables large-scale fleet deployments such as deploying
one application to every cluster, generating applications from Git
repositories, creating preview environments for pull requests, and
combining clusters and applications using matrix generators.

FluxCD has no direct equivalent to ApplicationSet.

### Project-Based Multi-Tenancy and RBAC

ArgoCD Projects provide logical isolation between teams by defining
deployment boundaries.

Projects can restrict: - Git repositories - Kubernetes clusters -
Namespaces - Resource types

Each Project supports its own RBAC policies, making it suitable for
multi-tenant environments.

Our Control Plane manages onboarding, routing, provisioning, and Project
creation, while ArgoCD enforces deployment authorization and
reconciliation.

### Rich User Interface

ArgoCD includes: - Application dashboard - Synchronization status -
Health status - Deployment history - Resource tree - Git commit
tracking - Live diff - Events - Rollback support

FluxCD does not provide a built-in UI and typically relies on additional
tooling.

### API Support

ArgoCD exposes comprehensive REST and gRPC APIs for creating
applications and projects, configuring RBAC, triggering synchronization,
retrieving status, deployment history, and health information. This
makes integration with our Control Plane straightforward.

Flux primarily relies on Kubernetes Custom Resources instead of a
centralized application API.

### Community and Ecosystem

ArgoCD is a CNCF Graduated project with more than 22,000 GitHub stars,
hundreds of contributors, extensive documentation, and widespread
enterprise adoption.

FluxCD is also CNCF Graduated but has a smaller ecosystem and community
footprint. Rancher Fleet has a significantly smaller community and is
primarily adopted within the Rancher ecosystem.

The larger ArgoCD community reduces long-term operational risk through
better documentation, faster issue resolution, richer integrations, and
greater community support.

## Why ArgoCD Best Fits Our Platform

Our Control Plane is responsible for tenant onboarding, application
onboarding, deployment routing, platform policies, and workflow
automation.

ArgoCD complements this architecture by providing:

-   Continuous reconciliation
-   Drift detection
-   Self-healing
-   Centralized multi-cluster management
-   Fleet deployment capabilities
-   ApplicationSet-based deployment automation
-   Project-based multi-tenancy
-   Enterprise-grade RBAC
-   Rich operational UI
-   Comprehensive APIs
-   Mature GitOps implementation
-   Large open-source community

While FluxCD and Rancher Fleet satisfy the core GitOps requirements of
reconciliation and multi-cluster deployments, ArgoCD offers a richer
operational model, centralized fleet management, stronger multi-tenancy
capabilities, and a superior application management experience, making
it the best fit for our platform.
