package com.argocd.platform.api.model.response;

import com.argocd.platform.api.model.response.argocd.ProjectClusterItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectResponse {

    private UUID id;
    private String name;
    private String description;
    private String status;
    private UUID createdBy;
    private Integer projectPartitionNumber;
    /** Clusters currently assigned to this project (name + namespaces). */
    private List<ProjectClusterItem> clusters;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
