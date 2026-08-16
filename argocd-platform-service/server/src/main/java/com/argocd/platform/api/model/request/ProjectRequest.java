package com.argocd.platform.api.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request body for creating or updating a project.
 *
 * <p>Cluster associations: each entry in {@code clusters} is resolved by id first,
 * then by name. Records are inserted into {@code project_clusters}.
 * On update, existing cluster associations are replaced.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRequest {

    @NotBlank(message = "Project name is required")
    private String name;

    private String description;

    @NotNull(message = "createdBy user ID is required")
    private UUID createdBy;

    /**
     * Optional list of clusters to associate with this project.
     * Each entry must have at least one of {@code id} or {@code name}.
     */
    @Valid
    private List<ClusterReference> clusters;
}
