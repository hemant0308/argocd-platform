package com.argocd.platform.api.model.request;

import com.argocd.platform.api.util.SuccessCondition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v1/failover}.
 *
 * <h3>Filter semantics</h3>
 * <p>All cluster selection criteria are grouped in the nested {@link FailoverFilter} object.
 * At least one filter field inside {@code filter} must be non-empty (validated by the service).
 *
 * <h3>Batch execution</h3>
 * <p>Clusters are sorted by name and divided into batches of {@code batchSize}.
 * The batch scheduler executes one batch at a time, updating
 * {@code clusters.control_plane_id} for each cluster in the batch and then waiting
 * for ArgoCD to confirm the {@code successCondition} for every cluster in the batch
 * before advancing to the next batch.
 *
 * <h3>Dry run</h3>
 * <p>When {@code dryRun = true}, the service resolves the filter, computes batch
 * assignments, and persists an operation row ({@code status = COMPLETED, dry_run = true})
 * for audit purposes — but does NOT create per-cluster rows and does NOT change
 * {@code clusters.control_plane_id}. The response contains the full plan preview including
 * the total number of applications that would be affected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailoverRequest {

    /**
     * Name of the control plane that selected clusters should be moved to.
     * Must exist in {@code control_planes.name}; returns 404 if not found.
     */
    @NotBlank(message = "targetControlPlane is required")
    private String targetControlPlane;

    /**
     * Cluster selection criteria. At least one field inside the filter must be
     * non-empty (validated by the service). See {@link FailoverFilter} for semantics.
     */
    private FailoverFilter filter;

    // -------------------------------------------------------------------------
    // Batch configuration
    // -------------------------------------------------------------------------

    /**
     * Number of clusters to migrate per batch. {@code null} means all resolved
     * clusters are placed in a single batch (no batching).
     * Must be ≥ 1 when specified.
     */
    @Min(value = 1, message = "batchSize must be at least 1")
    private Integer batchSize;

    /**
     * What must be true for every application on a migrated cluster before that
     * cluster is counted as CONFIRMED and the operation advances to the next batch.
     * Defaults to {@link SuccessCondition#SYNCED} when {@code null}.
     */
    private SuccessCondition successCondition;

    /**
     * If {@code true}, the filter is resolved and batches are planned but no
     * changes are persisted to {@code clusters} or {@code failover_operation_clusters}.
     * An operation row with {@code dry_run = true, status = COMPLETED} is created
     * for audit purposes. Defaults to {@code false}.
     */
    @Builder.Default
    private boolean dryRun = false;

    /**
     * Seconds to wait for a batch to reach CONFIRMED before transitioning the
     * operation to TIMED_OUT. Defaults to 600 (10 minutes) when {@code null}.
     * Must be ≥ 1 when specified.
     */
    @Min(value = 1, message = "batchTimeoutSeconds must be at least 1")
    private Integer batchTimeoutSeconds;
}
