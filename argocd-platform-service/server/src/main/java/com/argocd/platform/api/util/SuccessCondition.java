package com.argocd.platform.api.util;

/**
 * What must be true for every application on a migrated cluster before that cluster
 * is counted as {@link FailoverClusterStatus#CONFIRMED}.
 *
 * <p>The confirmation query also gates on {@code applications.updated_at > migrated_at}
 * so that pre-failover DB values (written by the old CP's ArgoCD) cannot satisfy the
 * condition — only events received after the cluster was migrated count.
 *
 * <ul>
 *   <li>{@code CREATED}  — the Application object exists on the new CP
 *       (application-partition ApplicationSet synced the manifest).</li>
 *   <li>{@code SYNCED}   — {@code applications.sync_status = SYNCED} on the new CP.</li>
 *   <li>{@code HEALTHY}  — {@code applications.health_status = HEALTHY} on the new CP.</li>
 * </ul>
 */
public enum SuccessCondition {
    CREATED,
    SYNCED,
    HEALTHY
}
