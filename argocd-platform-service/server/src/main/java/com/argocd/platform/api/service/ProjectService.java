package com.argocd.platform.api.service;

import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceAlreadyExistsException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ClusterReference;
import com.argocd.platform.api.model.request.ProjectRequest;
import com.argocd.platform.api.model.response.ProjectResponse;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ProjectsEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ClusterRepository clusterRepository;
    private final PartitionRepository partitionRepository;
    private final PartitionProperties partitionProperties;

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        // Reject duplicates before hitting the DB unique constraint
        if (projectRepository.findByName(request.getName()).isPresent()) {
            throw new ResourceAlreadyExistsException(
                    "Project with name '" + request.getName() + "' already exists.");
        }

        // Resolve cluster IDs from references (id takes precedence over name)
        List<UUID> clusterIds = resolveClusterIds(request.getClusters());

        // Resolve (or create) a stable project partition
        UUID partitionId = partitionRepository.resolvePartitionId(
                PartitionType.PROJECT, partitionProperties.getProjectTargetSize());

        ProjectsEntity entity = new ProjectsEntity()
                .setName(request.getName())
                .setDescription(request.getDescription())
                .setCreatedBy(request.getCreatedBy())
                .setProjectPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name());

        ProjectsEntity saved = projectRepository.save(entity);

        // Insert project-cluster associations
        if (!clusterIds.isEmpty()) {
            projectRepository.saveProjectClusters(saved.getId(), clusterIds);
        }

        return toResponse(saved);
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

        return toResponse(updated);
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

    private ProjectResponse toResponse(ProjectsEntity e) {
        return ProjectResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .status(e.getStatus())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
