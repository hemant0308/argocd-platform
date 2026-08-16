package com.argocd.platform.api.model.assignment;

/**
 * Strategy used to select a control plane for a new (or re-assigned) cluster.
 *
 * <ul>
 *   <li>{@link #EXPLICIT} — caller supplies the control-plane name via
 *       {@code ClusterRequest.controlPlane}; the platform looks it up by name.</li>
 *   <li>{@link #CONSISTENT_HASH} — the platform hashes {@code ClusterRequest.server}
 *       against the sorted list of available control planes and picks deterministically.</li>
 * </ul>
 *
 * <p>When {@code assignmentAlgorithm} is omitted from the request body the effective
 * algorithm is derived automatically:
 * <ul>
 *   <li>If {@code controlPlane} is non-blank → {@link #EXPLICIT}</li>
 *   <li>Otherwise → {@link #CONSISTENT_HASH}</li>
 * </ul>
 */
public enum ControlPlaneAssignmentAlgorithm {

    /**
     * Use the control plane whose name matches {@code ClusterRequest.controlPlane}.
     * Requires that field to be non-blank; fails with 400 if the name is unknown.
     */
    EXPLICIT,

    /**
     * Select the control plane via {@code Math.floorMod(server.hashCode(), count)}
     * over a stable-sorted (by UUID) list of available control planes.
     * Produces the same result for the same {@code server} URL across calls,
     * as long as the set of control planes does not change.
     */
    CONSISTENT_HASH
}
