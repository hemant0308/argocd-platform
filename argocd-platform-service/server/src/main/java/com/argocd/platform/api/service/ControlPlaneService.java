package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ControlPlaneRequest;
import com.argocd.platform.api.model.response.ControlPlaneResponse;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ControlPlaneService {

    private final ControlPlaneRepository controlPlaneRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ControlPlaneResponse create(ControlPlaneRequest request) {
        ControlPlanesEntity entity = new ControlPlanesEntity()
                .setName(request.getName())
                .setServer(request.getServer())
                .setStatus(ResourceStatus.UNKNOWN.name());

        ControlPlaneResponse response = controlPlaneRepository.save(entity, request.getEndpoint());
        // A new control plane appears in project-partitions (fan-out list) and project-groups.
        // Publish null partitionId to signal "clear all" to the cache invalidation listener.
        publishClearAll();
        return response;
    }

    @Transactional
    public ControlPlaneResponse update(UUID id, ControlPlaneRequest request) {
        ControlPlanesEntity existing = controlPlaneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + id));

        existing.setName(request.getName())
                .setServer(request.getServer());

        ControlPlaneResponse response = controlPlaneRepository.update(id, existing, request.getEndpoint());
        publishClearAll();
        return response;
    }

    /**
     * Returns all control planes including endpoint, ordered by id.
     */
    public List<ControlPlaneResponse> list() {
        return controlPlaneRepository.findAllWithEndpoint();
    }

    /**
     * Deletes a control plane by id.
     * Fails with 409 if clusters still reference this control plane.
     */
    @Transactional
    public void delete(UUID id) {
        controlPlaneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + id));
        controlPlaneRepository.deleteById(id);
        publishClearAll();
    }

    /**
     * Publishes a {@link PartitionChangedEvent} with a null partition ID, signalling
     * that the cache invalidation listener should clear all cached entries.
     *
     * <p>Used for control-plane mutations because they affect the project-partitions
     * fan-out list (every project partition includes all CP names) and may re-group
     * cluster-groups (clusters are grouped by CP name).
     */
    private void publishClearAll() {
        eventPublisher.publishEvent(new PartitionChangedEvent(this, null, null));
    }
}
