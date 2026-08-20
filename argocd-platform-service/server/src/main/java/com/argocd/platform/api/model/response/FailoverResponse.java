package com.argocd.platform.api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/failover} and {@code GET /api/v1/failover/{id}}.
 *
 * <p>On a successful POST:
 * <ul>
 *   <li>{@code status = PENDING} for a real run — the batch scheduler will pick it up.</li>
 *   <li>{@code status = COMPLETED, dryRun = true} for a dry run — no cluster changes made.</li>
 * </ul>
 *
 * <p>On a GET, {@code status}, {@code completedClusters}, and {@code currentBatch} reflect
 * the live state, and {@code clusters} shows the per-cluster migration progress.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailoverResponse {

    private UUID operationId;

    /**
     * Overall operation state (PENDING / AWAITING_BATCH_CONFIRMATION / COMPLETED /
     * TIMED_OUT / CANCELLED / FAILED).
     */
    private String status;

    private String targetControlPlane;

    private int totalClusters;

    /** Number of clusters that have reached CONFIRMED status. */
    private int completedClusters;

    /**
     * Batch currently being processed (1-indexed). {@code 0} before the scheduler
     * picks up the operation.
     */
    private int currentBatch;

    private int totalBatches;

    /**
     * Clusters per batch. {@code null} means all clusters are in a single batch
     * (effective batch size = totalClusters).
     */
    private Integer batchSize;

    /** What must be true for every application on a cluster before it is CONFIRMED. */
    private String successCondition;

    /**
     * {@code true} if this was a dry run — no changes were made to
     * {@code clusters.control_plane_id} and no per-cluster rows were persisted.
     */
    private boolean dryRun;

    /** Seconds to wait per batch before transitioning to TIMED_OUT. */
    private int batchTimeoutSeconds;

    /**
     * Per-cluster batch assignments and migration state.
     * Ordered by batch number, then cluster name.
     * On dry-run responses the {@code status} field of each item is {@code null}.
     */
    private List<ClusterBatchItem> clusters;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
