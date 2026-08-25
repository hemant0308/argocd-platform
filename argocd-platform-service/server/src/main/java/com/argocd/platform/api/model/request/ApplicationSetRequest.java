package com.argocd.platform.api.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request body for creating or updating a user-defined ApplicationSet.
 *
 * <p>Project resolution: {@code projectId} takes precedence over {@code projectName}.
 * At least one must be provided.
 *
 * <p>The ApplicationSet will be deployed to every control plane that hosts a cluster
 * belonging to the named project. Fan-out is derived at query time via:
 * {@code project → project_clusters → clusters → control_planes}.
 *
 * <p>{@code generators} and {@code template} are free-form JSON and map directly to
 * {@code spec.generators} and {@code spec.template} in the ArgoCD ApplicationSet.
 * No schema validation is enforced; the platform passes them verbatim to the
 * applicationset-registration Helm chart via JSONB.
 *
 * <p>{@code goTemplate} controls {@code spec.goTemplate} in the rendered ApplicationSet.
 * When {@code true}, ArgoCD evaluates template variables as {@code {{ .variable }}} Go
 * expressions. When {@code false} (default), ArgoCD uses the standard {@code {{variable}}}
 * syntax. Both pass through the Helm {@code toYaml} pipeline without re-evaluation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationSetRequest {

    /** Required on create; ignored on update (name is immutable). */
    private String name;

    /** Project UUID. Takes precedence over projectName when present. */
    private UUID projectId;

    /** Project name. Used for lookup when projectId is absent. */
    private String projectName;

    /**
     * ArgoCD ApplicationSet generator definitions. Maps to {@code spec.generators[]}.
     * Each entry is a free-form map matching one ArgoCD generator — list, git, matrix,
     * merge, clusters, scmProvider, pullRequest, etc.
     * At least one generator is required on both create and update.
     */
    private List<Map<String, Object>> generators;

    /**
     * ArgoCD ApplicationSet template definition. Maps to {@code spec.template}.
     * Must contain at minimum {@code metadata} and {@code spec} sub-keys.
     */
    private Map<String, Object> template;

    /**
     * When {@code true}, the rendered ApplicationSet has {@code spec.goTemplate: true}.
     * Defaults to {@code false} (ArgoCD standard {@code {{variable}}} syntax).
     */
    @Builder.Default
    private boolean goTemplate = false;
}
