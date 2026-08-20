package com.argocd.platform.api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-cluster detail in a {@link FailoverResponse}.
 *
 * <p>On a create (POST) response every item has {@code status = PENDING} (or {@code null}
 * for dry-run operations where no cluster rows are persisted).
 * On a GET response, {@code status} reflects the actual persisted value from
 * {@code failover_operation_clusters.status}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterBatchItem {

    private UUID clusterId;
    private String clusterName;

    /** Name of the control plane the cluster was on when the operation was created. */
    private String sourceControlPlane;

    /** 1-indexed batch number this cluster belongs to. */
    private int batchNumber;

    /**
     * Current migration status (PENDING / MIGRATED / CONFIRMED / FAILED).
     * {@code null} for dry-run operations (no cluster rows are persisted).
     */
    private String status;

    /** Timestamp when {@code clusters.control_plane_id} was updated for this cluster. */
    private LocalDateTime migratedAt;

    /** Timestamp when the success condition was satisfied for this cluster. */
    private LocalDateTime confirmedAt;
}
