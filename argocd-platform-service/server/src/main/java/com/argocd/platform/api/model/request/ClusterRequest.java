package com.argocd.platform.api.model.request;

import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.validation.ValidK8sLabels;
import com.argocd.platform.api.validation.ValidNamespacePatterns;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterRequest {

    @NotBlank(message = "Cluster name is required")
    private String name;

    @NotBlank(message = "Kubernetes API server endpoint is required")
    private String server;

    /**
     * Optional control-plane name reference.
     *
     * <p>When provided and {@code assignmentAlgorithm} is absent, the platform defaults
     * to {@link ControlPlaneAssignmentAlgorithm#EXPLICIT} and looks up this name in the DB.
     * When omitted, the platform defaults to
     * {@link ControlPlaneAssignmentAlgorithm#CONSISTENT_HASH} and selects a control plane
     * automatically.
     *
     * <p>Required (non-blank) when {@code assignmentAlgorithm = EXPLICIT}.
     */
    private String controlPlane;

    /**
     * Algorithm used to select the control plane for this cluster.
     *
     * <p>When {@code null} the effective algorithm is derived automatically:
     * <ul>
     *   <li>{@code controlPlane} non-blank → {@link ControlPlaneAssignmentAlgorithm#EXPLICIT}</li>
     *   <li>otherwise → {@link ControlPlaneAssignmentAlgorithm#CONSISTENT_HASH}</li>
     * </ul>
     */
    private ControlPlaneAssignmentAlgorithm assignmentAlgorithm;

    /**
     * Controls whether an UPDATE operation may reassign the cluster to a different
     * control plane.
     *
     * <p>Defaults to {@code false}: on PUT the existing control plane is always preserved
     * unless this flag is explicitly set to {@code true} in the request body.
     * On POST (create) this field is ignored; the resolver always runs.
     */
    @Builder.Default
    private boolean reassignControlPlane = false;

    /**
     * Optional list of namespace names or ArgoCD glob patterns that restrict this cluster
     * registration to a subset of namespaces.
     * <p>
     * Examples: {@code ["default", "team-a", "monitoring-*"]}
     * <p>
     * When {@code null} or omitted the cluster is registered at cluster-level (ArgoCD default).
     * Each entry must be {@code "*"}, a valid Kubernetes namespace name, or a glob prefix
     * ending with {@code "*"}.
     */
    @ValidNamespacePatterns
    private List<String> namespaces;

    /**
     * Optional Kubernetes-style labels applied to this cluster registration.
     * Used for selector-based routing and grouping in the platform.
     * <p>
     * Keys and values must follow Kubernetes label conventions:
     * optional {@code prefix/} DNS subdomain (max 253 chars) + name (max 63 chars);
     * values may be empty or follow the same alphanumeric/{@code -_.} rules.
     */
    @ValidK8sLabels
    private Map<String, String> labels;

    /**
     * Optional authentication configuration for connecting to the Kubernetes API server.
     * Accepted as a free-form JSON object — any shape is stored and returned as-is.
     * When {@code null}, no auth is configured (e.g. in-cluster service account).
     */
    private Map<String, Object> auth;
}
