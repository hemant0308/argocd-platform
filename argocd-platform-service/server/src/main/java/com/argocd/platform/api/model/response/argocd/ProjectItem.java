package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectItem {
    private UUID id;
    private String name;
    /** Clusters assigned to this project — used to build AppProject destinations. */
    private List<ProjectClusterItem> clusters;
}
