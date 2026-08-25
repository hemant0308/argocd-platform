package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a single user-defined ApplicationSet in the {@code applicationset-groups}
 * plugin response.
 *
 * <p>One {@code ApplicationSetItem} is included in the {@code applicationSets} array for
 * every control plane that hosts a cluster belonging to this ApplicationSet's project.
 * The same item (same {@code name}, {@code generatorSpec}, {@code templateSpec}) appears
 * in the response entry for each CP — the partition/CP fan-out decides which CPs receive it.
 *
 * <p>{@code generatorSpec} and {@code templateSpec} are passed verbatim to the
 * {@code applicationset-registration} Helm chart via {@code toJson/fromJsonArray}. The
 * chart serialises them back to YAML using {@code toYaml | nindent} — Helm does NOT
 * re-evaluate Go template tokens inside string values, so both ArgoCD standard
 * ({@code {{cluster}}}) and goTemplate ({@code {{ .cluster }}}) syntax pass through
 * untouched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationSetItem {
    /** Globally unique ApplicationSet name (base name + 5-hex-char hash suffix). */
    private String name;
    /** Name of the ArgoCD project this ApplicationSet belongs to. */
    private String projectName;
    /** When true, the rendered ApplicationSet has spec.goTemplate: true. */
    private boolean goTemplate;
    /** Maps to spec.generators[] in the ArgoCD ApplicationSet. */
    private List<Map<String, Object>> generatorSpec;
    /** Maps to spec.template in the ArgoCD ApplicationSet. */
    private Map<String, Object> templateSpec;
}
