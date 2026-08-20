package com.argocd.platform.api.util;

/**
 * Lifecycle states for a {@code failover_operations} row.
 *
 * <pre>
 * PENDING
 *   └─ scheduler picks up batch 1
 *        └─ AWAITING_BATCH_CONFIRMATION
 *               ├─ all clusters confirmed → next batch (loops back to AWAITING_BATCH_CONFIRMATION)
 *               │   └─ last batch confirmed → COMPLETED
 *               └─ batch_timeout_seconds elapsed → TIMED_OUT
 * CANCELLED  — caller invoked /cancel; no rollback
 * FAILED     — hard error (DB failure, invalid CP); not self-recoverable
 * </pre>
 */
public enum FailoverOperationStatus {
    PENDING,
    AWAITING_BATCH_CONFIRMATION,
    COMPLETED,
    TIMED_OUT,
    CANCELLED,
    FAILED
}
