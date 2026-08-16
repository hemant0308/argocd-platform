package com.argocd.platform.api.service;

import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ApplicationRequest;
import com.argocd.platform.api.model.response.ApplicationResponse;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import org.jooq.JSONB;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ProjectRepository projectRepository;
    private final ClusterRepository clusterRepository;
    private final PartitionRepository partitionRepository;
    private final PartitionProperties partitionProperties;

    @Transactional
    public ApplicationResponse create(ApplicationRequest request) {
        if (StringUtils.isBlank(request.getName())) {
            throw new InvalidRequestException("Application name is required");
        }
        if (request.getSources() == null || request.getSources().isEmpty()) {
            throw new InvalidRequestException("At least one application source is required");
        }
        UUID projectId = resolveProjectId(request);
        UUID clusterId = resolveClusterId(request);

        validateClusterInProject(projectId, clusterId);

        // Append a 5-hex-char suffix to guarantee global uniqueness of application names.
        // The suffixed name is what ArgoCD sees and what is returned in the response.
        String finalName = request.getName() + "-" + randomSuffix();

        // Resolve (or create) a stable application partition
        UUID partitionId = partitionRepository.resolvePartitionId(
                PartitionType.APPLICATION, partitionProperties.getApplicationTargetSize());

        ApplicationsEntity entity = new ApplicationsEntity()
                .setName(finalName)
                .setProjectId(projectId)
                .setClusterId(clusterId)
                .setApplicationPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name())
                .setGeneration(0L);

        ApplicationsEntity saved = applicationRepository.save(entity, request.getSources());
        return toResponse(saved, request.getSources());
    }

    @Transactional
    public ApplicationResponse update(UUID id, ApplicationRequest request) {
        if (request.getSources() == null || request.getSources().isEmpty()) {
            throw new InvalidRequestException("At least one application source is required");
        }
        ApplicationsEntity existing = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + id));

        UUID clusterId = resolveClusterId(request);

        validateClusterInProject(existing.getProjectId(), clusterId);

        // Partition and name are NEVER changed from the API.
        // Increment generation to signal desired-state change to ApplicationSet.
        existing.setClusterId(clusterId)
                .setGeneration(existing.getGeneration() + 1L);

        ApplicationsEntity updated = applicationRepository.update(id, existing, request.getSources());
        return toResponse(updated, request.getSources());
    }

    private static final TypeReference<List<Map<String, Object>>> SOURCES_TYPE =
            new TypeReference<>() {};

    /**
     * Returns all applications ordered by name, each with their sources JSONB deserialized.
     * Uses two queries to avoid N+1: one for entities, one map query for all sources.
     */
    public List<ApplicationResponse> list() {
        List<ApplicationsEntity> entities = applicationRepository.findAll();
        if (entities.isEmpty()) return List.of();
        Map<UUID, JSONB> sourcesMap = applicationRepository.findAllSourcesMap();
        return entities.stream()
                .map(e -> toResponse(e,
                        applicationRepository.fromJsonb(sourcesMap.get(e.getId()), SOURCES_TYPE)))
                .collect(Collectors.toList());
    }

    /**
     * Deletes an application by id.
     */
    @Transactional
    public void delete(UUID id) {
        applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + id));
        applicationRepository.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the given cluster is associated with the given project via
     * {@code project_clusters}. Throws {@link InvalidRequestException} if not.
     */
    private void validateClusterInProject(UUID projectId, UUID clusterId) {
        if (!projectRepository.isClusterInProject(projectId, clusterId)) {
            throw new InvalidRequestException(
                    "Cluster '" + clusterId + "' is not part of project '" + projectId + "'. " +
                    "Add the cluster to the project before creating an application targeting it.");
        }
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

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Generates a 5-character lowercase hex string (00000–fffff, ~1 M combinations)
     * appended to the user-supplied base name to produce a globally unique ArgoCD
     * application name without requiring an explicit uniqueness check.
     */
    private static String randomSuffix() {
        return String.format("%05x", ThreadLocalRandom.current().nextInt(1 << 20));
    }

    private ApplicationResponse toResponse(ApplicationsEntity e, List<Map<String, Object>> sources) {
        return ApplicationResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .projectId(e.getProjectId())
                .clusterId(e.getClusterId())
                .status(e.getStatus())
                .generation(e.getGeneration())
                .sources(sources)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
