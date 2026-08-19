package com.argocd.platform.api.util;

/**
 * Deletion state machine for applications.
 *
 * <p>State transitions:
 * <pre>
 *   null
 *    ├─► SOFT_DELETE ──────────────────────────► deleted_at set (on-deleted or timeout)
 *    └─► HARD_DELETE ─► AWAITING_PRUNE ──────► deleted_at set (on-deleted)
 * </pre>
 *
 * <ul>
 *   <li>{@code SOFT_DELETE} — deletion requested; app removed from plugin response immediately;
 *       ArgoCD prunes the Application without cascade (no finalizer). The {@code on-deleted}
 *       ArgoCD notification sets {@code deleted_at}; a scheduler timeout is the fallback.</li>
 *   <li>{@code HARD_DELETE} — deletion requested with cascade; app remains in plugin response
 *       with {@code hardDelete: true} so ArgoCD syncs the {@code resources-finalizer} on the
 *       Application. A scheduler task transitions this to {@code AWAITING_PRUNE} after the
 *       configured delay (≥ 2× ArgoCD poll interval), ensuring the finalizer has been synced.</li>
 *   <li>{@code AWAITING_PRUNE} — finalizer confirmed synced; app removed from plugin response;
 *       ArgoCD prunes the Application with cascade, deleting all user-managed resources.
 *       The {@code on-deleted} ArgoCD notification sets {@code deleted_at}.</li>
 * </ul>
 *
 * <p>Terminal state: {@code deleted_at IS NOT NULL} — row is kept for audit; all management
 * and plugin APIs exclude these rows. {@code deletion_mode} is cleared when {@code deleted_at}
 * is set.
 *
 * <p>Guard: any mutating operation on an application (update) is rejected with 409 when
 * {@code deletion_mode IS NOT NULL OR deleted_at IS NOT NULL}.
 */
public enum DeletionMode {
    SOFT_DELETE,
    HARD_DELETE,
    AWAITING_PRUNE
}
