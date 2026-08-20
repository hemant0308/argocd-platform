package com.argocd.platform.api.model.request;

import com.argocd.platform.api.util.SuccessCondition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/failover}.
 *
 * <h3>Filter semantics</h3>
 * <ul>
 *   <li>AND between different filter fields — all specified fields must match.</li>
 *   <li>OR between items in the same list field (e.g. multiple {@code labelSelectors} entries).</li>
 *   <li>AND within each {@code labelSelectors} entry — all key/value pairs must match.</li>
 *   <li>Label values are POSIX regex patterns (case-sensitive, unanchored, Postgres {@code ~}).</li>
 *   <li>At least one filter field must be non-empty (validated by the service).</li>
 *   <li>Clusters already assigned to {@code targetControlPlane} are always excluded.</li>
 * </ul>
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
 * {@code clusters.control_plane_id}. The response contains the full plan preview.
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

    // -------------------------------------------------------------------------
    // Filter fields — at least one must be non-empty (enforced by service)
    // -------------------------------------------------------------------------

    /**
     * Explicit cluster IDs to include. ANDed with other filter fields.
     */
    private List<UUID> clusterIds;

    /**
     * Explicit cluster names to include. ANDed with other filter fields.
     */
    private List<String> clusterNames;

    /**
     * Label selector list. Each entry is a map of {@code {key: regexValue}}.
     * Semantics: OR between entries, AND within each entry, values are
     * POSIX regex (Postgres {@code ~}, case-sensitive, unanchored).
     * Example: {@code [{"env":"prod","region":"us-east.*"},{"team":"platform"}]}
     * matches clusters where ({@code env ~ "prod"} AND {@code region ~ "us-east.*"})
     * OR {@code team ~ "platform"}.
     */
    private List<Map<String, String>> labelSelectors;

    /**
     * Restrict selection to clusters currently assigned to these control planes.
     * ANDed with other filter fields.
     */
    private List<String> sourceControlPlanes;

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
