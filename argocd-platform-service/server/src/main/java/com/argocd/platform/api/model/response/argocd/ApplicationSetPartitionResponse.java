package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One entry in the {@code applicationset-partitions} plugin response.
 *
 * <p>The Level 1 ApplicationSet polls this list at {@code requeueAfterSeconds: 10}.
 * When {@code generation} changes, ArgoCD re-renders the Level 2 Application (Helm chart
 * re-eval), which changes the Level 3 ApplicationSet spec → immediate reconcile.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationSetPartitionResponse {
    private UUID id;
    private int partitionNumber;
    private long generation;
    private int applicationSetCount;
}
