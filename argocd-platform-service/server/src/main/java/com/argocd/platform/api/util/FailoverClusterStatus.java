package com.argocd.platform.api.util;

/**
 * Per-cluster migration states for a {@code failover_operation_clusters} row.
 *
 * <pre>
 * PENDING     — assigned to a future batch; clusters.control_plane_id not yet changed
 * MIGRATED    — clusters.control_plane_id updated to target CP; awaiting ArgoCD confirmation
 * CONFIRMED   — success condition satisfied (all apps on this cluster pass the condition
 *               AND apps.updated_at > migrated_at, preventing stale pre-failover values)
 * FAILED      — batch timed out while this cluster was MIGRATED; operation → TIMED_OUT
 * ROLLED_BACK — cluster was reverted to source CP by /rollback; clusters.control_plane_id
 *               and partition FKs restored to their pre-migration values from FOC source columns
 * </pre>
 *
 * <p>{@code ROLLED_BACK} is a Java-level enum only; the DB column is {@code VARCHAR(50)}
 * so no Liquibase migration is required to add this value.
 */
public enum FailoverClusterStatus {
    PENDING,
    MIGRATED,
    CONFIRMED,
    FAILED,
    ROLLED_BACK
}
