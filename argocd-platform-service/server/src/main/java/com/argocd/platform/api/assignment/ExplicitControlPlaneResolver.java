package com.argocd.platform.api.assignment;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.model.request.ClusterRequest;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves a control plane by looking up the name provided in
 * {@link ClusterRequest#getControlPlane()} directly in the database.
 *
 * <p>This resolver is selected when:
 * <ul>
 *   <li>{@code assignmentAlgorithm = EXPLICIT} is set in the request, or</li>
 *   <li>{@code assignmentAlgorithm} is absent <em>and</em> {@code controlPlane} is non-blank
 *       (the default-derivation rule in {@code ClusterService}).</li>
 * </ul>
 *
 * <p>Throws {@link InvalidRequestException} (400) when {@code controlPlane} is blank,
 * and {@link ResourceNotFoundException} (404) when no control plane with that name exists.
 */
@Component
@RequiredArgsConstructor
public class ExplicitControlPlaneResolver implements ControlPlaneResolver {

    private final ControlPlaneRepository controlPlaneRepository;

    @Override
    public ControlPlaneAssignmentAlgorithm supportedAlgorithm() {
        return ControlPlaneAssignmentAlgorithm.EXPLICIT;
    }

    @Override
    public UUID resolve(ClusterRequest request) {
        String name = request.getControlPlane();
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException(
                    "controlPlane name must not be blank when assignmentAlgorithm is EXPLICIT");
        }
        return controlPlaneRepository.findByName(name)
                .map(cp -> cp.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + name));
    }
}
