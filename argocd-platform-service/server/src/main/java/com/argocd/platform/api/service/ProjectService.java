package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceAlreadyExistsException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ClusterReference;
import com.argocd.platform.api.model.request.ProjectRequest;
import com.argocd.platform.api.model.response.ProjectResponse;
import com.argocd.platform.api.model.response.argocd.ProjectClusterItem;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ProjectsEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    /** Default user ID applied to {@code created_by} when not supplied in the request. */
    public static final UUID DEFAULT_CREATED_BY =
            UUID.fromString("76012b17-c3f5-4956-95a5-d9b3fe14f838");

    private final ProjectRepository projectRepository;
    private final ClusterRepository clusterRepository;
    private final PartitionService partitionService;
    private final PartitionProperties partitionProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        if (StringUtils.isBlank(request.getName())) {
            throw new InvalidRequestException("Project name is required");
        }

        // Reject duplicates before hitting the DB unique constraint
        if (projectRepository.findByName(request.getName()).isPresent()) {
            throw new ResourceAlreadyExistsException(
                    "Project with name '" + request.getName() + "' already exists.");
        }

        // Resolve cluster IDs from references (id takes precedence over name)
        List<UUID> clusterIds = resolveClusterIds(request.getClusters());

        // Resolve (or create) a stable project partition (project partitions remain global)
        UUID partitionId = partitionService.resolveProjectPartitionId(
                partitionProperties.getProjectTargetSize());

        UUID createdBy = request.getCreatedBy() != null
                ? request.getCreatedBy() : DEFAULT_CREATED_BY;

        ProjectsEntity entity = new ProjectsEntity()
                .setName(request.getName())
                .setDescription(request.getDescription())
                .setCreatedBy(createdBy)
                .setProjectPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name());

        ProjectsEntity saved = projectRepository.save(entity);

        // Insert project-cluster associations
        if (!clusterIds.isEmpty()) {
            projectRepository.saveProjectClusters(saved.getId(), clusterIds);
        }

        eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.PROJECT));

        List<ProjectClusterItem> clusters = projectRepository
                .findClustersForProjects(List.of(saved.getId()))
                .getOrDefault(saved.getId(), List.of());
        return toResponse(saved, clusters);
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest request) {
        ProjectsEntity existing = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + id));

        // Resolve cluster IDs from references
        List<UUID> clusterIds = resolveClusterIds(request.getClusters());

        // Partition and name are NEVER changed from the API
        existing.setDescription(request.getDescription());

        ProjectsEntity updated = projectRepository.update(id, existing);

        // Replace cluster associations when clusters are provided in the request
        if (!clusterIds.isEmpty()) {
            projectRepository.deleteProjectClusters(id);
            projectRepository.saveProjectClusters(id, clusterIds);
        }

        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, existing.getProjectPartitionId(), PartitionType.PROJECT));

        List<ProjectClusterItem> clusters = projectRepository
                .findClustersForProjects(List.of(id))
                .getOrDefault(id, List.of());
        return toResponse(updated, clusters);
    }

    /**
     * Resolves a list of cluster UUIDs from {@link ClusterReference} entries.
     * For each entry: if {@code id} is present, validate it exists and use it;
     * otherwise look up by {@code name}.
     */
    private List<UUID> resolveClusterIds(List<ClusterReference> references) {
        if (CollectionUtils.isEmpty(references)) {
            return List.of();
        }

        List<UUID> resolved = new ArrayList<>();
        for (ClusterReference ref : references) {
            if (ref.getId() != null) {
                clusterRepository.findById(ref.getId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Cluster not found with id: " + ref.getId()));
                resolved.add(ref.getId());
            } else if (StringUtils.isNotBlank(ref.getName())) {
                UUID clusterId = clusterRepository.findByName(ref.getName())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Cluster not found with name: " + ref.getName()))
                        .getId();
                resolved.add(clusterId);
            } else {
                throw new InvalidRequestException(
                        "Each cluster reference must have either an id or a name");
            }
        }
        return resolved;
    }

    /**
     * Returns all projects ordered by name, each carrying its assigned clusters.
     * Uses two queries to avoid N+1.
     */
    public List<ProjectResponse> list() {
        List<ProjectsEntity> all = projectRepository.findAll();
        if (all.isEmpty()) return List.of();
        List<UUID> ids = all.stream().map(ProjectsEntity::getId).collect(Collectors.toList());
        Map<UUID, List<ProjectClusterItem>> clusterMap = projectRepository.findClustersForProjects(ids);
        return all.stream()
                .map(p -> toResponse(p, clusterMap.getOrDefault(p.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * Deletes a project by id.
     * WARNING: all associated applications are also deleted via DB CASCADE.
     */
    @Transactional
    public void delete(UUID id) {
        ProjectsEntity existing = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
        UUID partitionId = existing.getProjectPartitionId();
        projectRepository.deleteById(id);
        eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.PROJECT));
    }

    private ProjectResponse toResponse(ProjectsEntity e, List<ProjectClusterItem> clusters) {
        return ProjectResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .status(e.getStatus())
                .createdBy(e.getCreatedBy())
                .clusters(clusters)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
