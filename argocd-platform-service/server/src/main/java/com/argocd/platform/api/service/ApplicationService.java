package com.argocd.platform.api.service;

import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ApplicationRequest;
import com.argocd.platform.api.model.response.ApplicationResponse;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.ApplicationSourceRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationSourcesEntity;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationSourceRepository applicationSourceRepository;
    private final ProjectRepository projectRepository;
    private final ClusterRepository clusterRepository;
    private final PartitionRepository partitionRepository;
    private final PartitionProperties partitionProperties;

    @Transactional
    public ApplicationResponse create(ApplicationRequest request) {
        UUID projectId = resolveProjectId(request);
        UUID clusterId = resolveClusterId(request);

        // Resolve (or create) a stable application partition
        UUID partitionId = partitionRepository.resolvePartitionId(
                PartitionType.APPLICATION, partitionProperties.getApplicationTargetSize());

        ApplicationsEntity entity = new ApplicationsEntity()
                .setName(request.getName())
                .setProjectId(projectId)
                .setClusterId(clusterId)
                .setApplicationPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name())
                .setGeneration(0L);

        ApplicationsEntity saved = applicationRepository.save(entity);

        // Persist sources linked to the newly created application id
        applicationSourceRepository.saveAll(buildSourceEntities(saved.getId(), request));

        return toResponse(saved);
    }

    @Transactional
    public ApplicationResponse update(UUID id, ApplicationRequest request) {
        ApplicationsEntity existing = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + id));

        UUID clusterId = resolveClusterId(request);

        // Partition is NEVER changed from the API.
        // Increment generation to signal desired-state change to ApplicationSet.
        existing.setName(request.getName())
                .setClusterId(clusterId)
                .setGeneration(existing.getGeneration() + 1L);

        ApplicationsEntity updated = applicationRepository.update(id, existing);

        // Replace sources: delete old, insert new
        applicationSourceRepository.deleteByApplicationId(id);
        applicationSourceRepository.saveAll(buildSourceEntities(id, request));

        return toResponse(updated);
    }

    /**
     * Resolves the project UUID.
     * Uses {@code projectId} (with DB validation) when present;
     * falls back to lookup by {@code projectName}.
     */
    private UUID resolveProjectId(ApplicationRequest request) {
        if (request.getProjectId() != null) {
            projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project not found with id: " + request.getProjectId()));
            return request.getProjectId();
        }
        if (StringUtils.isNotBlank(request.getProjectName())) {
            return projectRepository.findByName(request.getProjectName())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project not found with name: " + request.getProjectName()))
                    .getId();
        }
        throw new InvalidRequestException("Either projectId or projectName must be provided");
    }

    /**
     * Resolves the cluster UUID.
     * Uses {@code clusterId} (with DB validation) when present;
     * falls back to lookup by {@code clusterName}.
     */
    private UUID resolveClusterId(ApplicationRequest request) {
        if (request.getClusterId() != null) {
            clusterRepository.findById(request.getClusterId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Cluster not found with id: " + request.getClusterId()));
            return request.getClusterId();
        }
        if (StringUtils.isNotBlank(request.getClusterName())) {
            return clusterRepository.findByName(request.getClusterName())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Cluster not found with name: " + request.getClusterName()))
                    .getId();
        }
        throw new InvalidRequestException("Either clusterId or clusterName must be provided");
    }

    private List<ApplicationSourcesEntity> buildSourceEntities(UUID applicationId, ApplicationRequest request) {
        return request.getSources().stream()
                .map(src -> new ApplicationSourcesEntity()
                        .setApplicationId(applicationId)
                        .setRepoUrl(src.getRepoUrl())
                        .setRevision(src.getRevision())
                        .setPath(src.getPath())
                        .setChart(src.getChart())
                        .setValues(src.getValues())
                        .setSourceOrder(src.getSourceOrder()))
                .collect(Collectors.toList());
    }

    private ApplicationResponse toResponse(ApplicationsEntity e) {
        return ApplicationResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .projectId(e.getProjectId())
                .clusterId(e.getClusterId())
                .status(e.getStatus())
                .generation(e.getGeneration())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
