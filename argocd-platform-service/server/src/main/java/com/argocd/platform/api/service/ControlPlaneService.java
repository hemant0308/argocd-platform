package com.argocd.platform.api.service;

import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ControlPlaneRequest;
import com.argocd.platform.api.model.response.ControlPlaneResponse;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ControlPlaneService {

    private final ControlPlaneRepository controlPlaneRepository;

    @Transactional
    public ControlPlaneResponse create(ControlPlaneRequest request) {
        ControlPlanesEntity entity = new ControlPlanesEntity()
                .setName(request.getName())
                .setServer(request.getServer())
                .setStatus(ResourceStatus.UNKNOWN.name());

        return controlPlaneRepository.save(entity, request.getEndpoint());
    }

    @Transactional
    public ControlPlaneResponse update(UUID id, ControlPlaneRequest request) {
        ControlPlanesEntity existing = controlPlaneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + id));

        existing.setName(request.getName())
                .setServer(request.getServer());

        return controlPlaneRepository.update(id, existing, request.getEndpoint());
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
    }
}
