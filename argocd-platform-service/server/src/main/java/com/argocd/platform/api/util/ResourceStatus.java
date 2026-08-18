package com.argocd.platform.api.util;

/**
 * Lifecycle status shared by all platform resources
 * (control planes, clusters, projects, applications).
 *
 * <ul>
 *   <li>{@code UNKNOWN}  — initial state; ArgoCD has not reported back yet</li>
 *   <li>{@code SYNCING}  — intermediate: Progressing health reported by ArgoCD</li>
 *   <li>{@code ACTIVE}   — terminal-healthy: Synced + Healthy</li>
 *   <li>{@code DEGRADED} — terminal-unhealthy: Degraded health reported by ArgoCD</li>
 *   <li>{@code ERROR}    — terminal-error: Sync Failed reported by ArgoCD</li>
 * </ul>
 */
public enum ResourceStatus {

    UNKNOWN,
    SYNCING,
    ACTIVE,
    DEGRADED,
    ERROR;
}
