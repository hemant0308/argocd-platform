package com.argocd.platform.api.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request body for creating or updating an application.
 *
 * <p>Project resolution: {@code projectId} takes precedence over {@code projectName}.
 * At least one must be provided.
 *
 * <p>Cluster resolution: {@code clusterId} takes precedence over {@code clusterName}.
 * At least one must be provided.
 *
 * <p>Sources are free-form JSON objects — any ArgoCD source shape is accepted and
 * stored as-is. No fixed schema is enforced; the platform passes the list verbatim
 * to ArgoCD via the application-registration Helm chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationRequest {

    @NotBlank(message = "Application name is required")
    private String name;

    /** Project UUID. Validated against DB when present. Takes precedence over projectName. */
    private UUID projectId;

    /** Project name. Used for lookup when projectId is absent. */
    private String projectName;

    /** Cluster UUID. Validated against DB when present. Takes precedence over clusterName. */
    private UUID clusterId;

    /** Cluster name. Used for lookup when clusterId is absent. */
    private String clusterName;

    /**
     * ArgoCD source definitions. Each entry is a free-form map matching the ArgoCD
     * Application {@code spec.source} / {@code spec.sources} schema — repoURL, path,
     * chart, targetRevision, helm, kustomize, directory, plugin, ref, etc.
     * At least one source is required.
     */
    @NotEmpty(message = "At least one application source is required")
    private List<Map<String, Object>> sources;
}
