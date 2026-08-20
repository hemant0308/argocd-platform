package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterPartitionResponse {
    private UUID id;
    private Integer partitionNumber;
    /** Name of the control plane that owns this partition (CP-scoped, Option B). */
    private String controlPlaneName;
    private Long generation;
    private Integer clusterCount;
}
