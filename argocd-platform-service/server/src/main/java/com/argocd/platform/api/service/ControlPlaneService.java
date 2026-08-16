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
                .setCapacity(request.getCapacity())
                .setStatus(ResourceStatus.UNKNOWN.name());

        ControlPlanesEntity saved = controlPlaneRepository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    public ControlPlaneResponse update(UUID id, ControlPlaneRequest request) {
        ControlPlanesEntity existing = controlPlaneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + id));

        existing.setName(request.getName())
                .setServer(request.getServer())
                .setCapacity(request.getCapacity());

        ControlPlanesEntity updated = controlPlaneRepository.update(id, existing);
        return toResponse(updated);
    }

    private ControlPlaneResponse toResponse(ControlPlanesEntity e) {
        return ControlPlaneResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .server(e.getServer())
                .status(e.getStatus())
                .capacity(e.getCapacity())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
