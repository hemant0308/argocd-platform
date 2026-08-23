package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceAlreadyExistsException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ApplicationRequest;
import com.argocd.platform.api.model.response.ApplicationResponse;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.DeletionMode;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
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
    private final PartitionService partitionService;
    private final PartitionProperties partitionProperties;
    private final JsonbUtils jsonbUtils;
    private final ApplicationEventPublisher eventPublisher;

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

        // Global partition: partition number is globally unique across all control planes (Option A).
        // CP association is derived at query time from clusters.control_plane_id — never stored
        // on the partition. Control planes are stateless.
        UUID partitionId = partitionService.resolveApplicationPartition(
                partitionProperties.getApplicationTargetSize());

        ApplicationsEntity entity = new ApplicationsEntity()
                .setName(finalName)
                .setProjectId(projectId)
                .setClusterId(clusterId)
                .setApplicationPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name())
                .setGeneration(0L);

        ApplicationsEntity saved = applicationRepository.save(entity, request.getSources());
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, partitionId, PartitionType.APPLICATION));
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

        // Reject mutations while deletion is in progress or the row is tombstoned.
        // deletion_mode IS NOT NULL → initiation phase (SOFT_DELETE, HARD_DELETE, AWAITING_PRUNE)
        // deleted_at IS NOT NULL    → completion phase (soft-tombstone, kept for audit)
        if (applicationRepository.isBeingDeleted(id)) {
            throw new ResourceAlreadyExistsException(
                    "Application '" + existing.getName() + "' is being deleted and cannot be modified");
        }

        UUID clusterId = resolveClusterId(request);

        validateClusterInProject(existing.getProjectId(), clusterId);

        // Partition and name are NEVER changed from the API.
        // Increment generation to signal desired-state change to ApplicationSet.
        existing.setClusterId(clusterId)
                .setGeneration(existing.getGeneration() + 1L);

        ApplicationsEntity updated = applicationRepository.update(id, existing, request.getSources());
        // Bump partition generation so ArgoCD's application-partition-{N}-{cp} Application
        // carries the new generation in its label on next sync, enabling the status service
        // to correctly anchor deletion_partition_generation comparisons.
        partitionService.bumpApplicationPartitionGeneration(existing.getApplicationPartitionId());
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, existing.getApplicationPartitionId(), PartitionType.APPLICATION));
        return toResponse(updated, request.getSources());
    }

    private static final TypeReference<List<Map<String, Object>>> SOURCES_TYPE =
            new TypeReference<>() {};

    /**
     * Returns all active (non-deleted) applications ordered by name, each with their
     * sources JSONB deserialized. Uses two queries to avoid N+1: one for entities,
     * one map query for all sources.
     */
    public List<ApplicationResponse> list() {
        List<ApplicationsEntity> entities = applicationRepository.findAll();
        if (entities.isEmpty()) return List.of();
        Map<UUID, JSONB> sourcesMap = applicationRepository.findAllSourcesMap();
        return entities.stream()
                .map(e -> toResponse(e,
                        jsonbUtils.fromJsonb(sourcesMap.get(e.getId()), SOURCES_TYPE)))
                .collect(Collectors.toList());
    }

    /**
     * Initiates deletion of an application.
     *
     * <p>This does NOT physically delete the database row. Instead it enters the
     * deletion state machine and publishes a {@link PartitionChangedEvent} so the
     * plugin generator cache is refreshed immediately:
     *
     * <ul>
     *   <li>Soft delete ({@code hardDelete = false}): sets {@code deletion_mode = SOFT_DELETE};
     *       app disappears from the plugin response; ArgoCD prunes the Application without
     *       cascade (no finalizer). The {@code on-deleted} ArgoCD notification sets
     *       {@code deleted_at}; a scheduler timeout is the fallback.</li>
     *   <li>Hard delete ({@code hardDelete = true}): sets {@code deletion_mode = HARD_DELETE};
     *       app remains in plugin response with {@code hardDelete = true} so ArgoCD syncs
     *       the {@code resources-finalizer}. After the configured scheduler delay the mode
     *       advances to {@code AWAITING_PRUNE}; the app disappears from the response; ArgoCD
     *       prunes with cascade. The {@code on-deleted} notification sets {@code deleted_at}.</li>
     * </ul>
     *
     * <p>A 409 is returned if deletion is already in progress or the row is tombstoned.
     *
     * @param id         the application UUID
     * @param hardDelete {@code true} for cascade (resource-finalizer) deletion
     */
    @Transactional
    public void initiateDelete(UUID id, boolean hardDelete) {
        ApplicationsEntity existing = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + id));

        if (applicationRepository.isBeingDeleted(id)) {
            throw new ResourceAlreadyExistsException(
                    "Application '" + existing.getName() + "' deletion is already in progress");
        }

        String mode = hardDelete ? DeletionMode.HARD_DELETE.name() : DeletionMode.SOFT_DELETE.name();

        // Atomically bump the partition generation in this same transaction.
        // For HARD_DELETE: the new generation is stored in deletion_partition_generation so the
        // status service can confirm — when application-partition-{N}-{cp} syncs at generation m —
        // that the manifest carrying the resources-finalizer was included in that sync, and only
        // then advance to AWAITING_PRUNE.  SOFT_DELETE does not need the generation stored
        // (there is no finalizer to confirm), but we still bump it to keep the counter monotonic.
        long newGeneration = partitionService.bumpApplicationPartitionGeneration(
                existing.getApplicationPartitionId());
        Long deletionPartitionGeneration = hardDelete ? newGeneration : null;

        applicationRepository.initiateDeletion(id, mode, deletionPartitionGeneration);

        // Refresh plugin cache so the deletion state is reflected on the next ArgoCD poll:
        //   SOFT_DELETE   → app disappears from response, ArgoCD prunes without cascade
        //   HARD_DELETE   → app appears with hardDelete: true, ArgoCD syncs finalizer
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, existing.getApplicationPartitionId(), PartitionType.APPLICATION));
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
