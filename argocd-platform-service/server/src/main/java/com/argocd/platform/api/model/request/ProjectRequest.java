package com.argocd.platform.api.model.request;

import jakarta.validation.Valid;
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
 *
 * <p>{@code name} and {@code createdBy} are only required on create; the service
 * validates this manually so that the same DTO can be reused for updates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRequest {

    /** Required on create; ignored on update (name is immutable). */
    private String name;

    private String description;

    /**
     * ID of the user creating the project.
     * Defaults to {@link com.argocd.platform.api.service.ProjectService#DEFAULT_CREATED_BY}
     * when not supplied.
     */
    private UUID createdBy;

    /**
     * Optional list of clusters to associate with this project.
     * Each entry must have at least one of {@code id} or {@code name}.
     */
    @Valid
    private List<ClusterReference> clusters;
}
