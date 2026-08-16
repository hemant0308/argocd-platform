package com.argocd.platform.api.assignment;

import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.model.request.ClusterRequest;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Assigns a control plane by hashing the cluster's {@code server} URL against
 * the sorted list of available control planes.
 *
 * <p>Algorithm:
 * <pre>
 *   sorted  = controlPlanes sorted by UUID (stable, deterministic)
 *   index   = Math.floorMod(server.hashCode(), sorted.size())
 *   result  = sorted.get(index).getId()
 * </pre>
 *
 * <p>{@link Math#floorMod} is used instead of {@code Math.abs(hash) % count} to avoid the
 * well-known pitfall where {@code Math.abs(Integer.MIN_VALUE)} returns a negative value.
 *
 * <p>The same {@code server} URL will consistently map to the same control plane as long
 * as the set of available control planes does not change in size. Adding or removing a
 * control plane will shift some assignments — this is expected behaviour for a simple
 * modulo-hash scheme; future improvements could layer a consistent-hash ring on top.
 *
 * <p>Throws {@link ResourceNotFoundException} (404) when no control planes exist in the DB.
 */
@Component
@RequiredArgsConstructor
public class ConsistentHashControlPlaneResolver implements ControlPlaneResolver {

    private final ControlPlaneRepository controlPlaneRepository;

    @Override
    public ControlPlaneAssignmentAlgorithm supportedAlgorithm() {
        return ControlPlaneAssignmentAlgorithm.CONSISTENT_HASH;
    }

    @Override
    public UUID resolve(ClusterRequest request) {
        List<ControlPlanesEntity> all = controlPlaneRepository.findAll();
        if (all.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No control planes available for automatic assignment");
        }

        // Sort by UUID string for a stable, reproducible ordering
        List<ControlPlanesEntity> sorted = all.stream()
                .sorted(Comparator.comparing(cp -> cp.getId().toString()))
                .toList();

        int hash = request.getServer().hashCode();
        int index = Math.floorMod(hash, sorted.size());
        return sorted.get(index).getId();
    }
}
