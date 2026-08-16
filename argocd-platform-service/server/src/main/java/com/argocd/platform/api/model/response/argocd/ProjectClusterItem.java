package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Minimal cluster descriptor attached to a {@link ProjectItem} for use by the
 * {@code project-groups} plugin endpoint.
 *
 * <p>Consumed by the {@code project-registration} Helm chart to build
 * ArgoCD AppProject {@code spec.destinations} entries:
 * <ul>
 *   <li>If {@code namespaces} is non-empty, one destination per namespace is emitted.</li>
 *   <li>If {@code namespaces} is null or empty, a single wildcard destination
 *       ({@code namespace: '*'}) is emitted for that cluster.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectClusterItem {

    /** ArgoCD cluster name — matches the cluster secret's {@code stringData.name}. */
    private String name;

    /**
     * Optional namespace whitelist for this cluster.
     * {@code null} / empty → allow all namespaces ({@code namespace: '*'}).
     */
    private List<String> namespaces;
}
