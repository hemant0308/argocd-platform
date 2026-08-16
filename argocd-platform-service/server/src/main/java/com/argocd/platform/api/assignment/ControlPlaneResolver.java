package com.argocd.platform.api.assignment;

import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.model.request.ClusterRequest;

import java.util.UUID;

/**
 * Strategy interface for resolving which control plane a cluster should be assigned to.
 *
 * <p>Each implementation declares the {@link ControlPlaneAssignmentAlgorithm} it handles
 * via {@link #supportedAlgorithm()}. At startup, {@code ClusterService} collects all
 * Spring-managed {@code ControlPlaneResolver} beans and builds a
 * {@code Map<ControlPlaneAssignmentAlgorithm, ControlPlaneResolver>} keyed by that value,
 * so dispatching to the correct strategy requires no switch statements.
 *
 * <p>To add a new algorithm, implement this interface, annotate with {@code @Component},
 * and return the new enum constant from {@link #supportedAlgorithm()}.
 */
public interface ControlPlaneResolver {

    /**
     * Returns the algorithm this resolver handles.
     * Must be unique across all resolver beans in the application context.
     */
    ControlPlaneAssignmentAlgorithm supportedAlgorithm();

    /**
     * Resolves the UUID of the control plane to assign to the cluster described by
     * {@code request}.
     *
     * @param request the incoming cluster create/update request
     * @return the control-plane UUID to be stored on the cluster record
     * @throws com.argocd.platform.api.exception.ResourceNotFoundException if no suitable
     *         control plane can be found
     * @throws com.argocd.platform.api.exception.InvalidRequestException if the request
     *         lacks data required by this algorithm (e.g. blank name for {@code EXPLICIT})
     */
    UUID resolve(ClusterRequest request);
}
