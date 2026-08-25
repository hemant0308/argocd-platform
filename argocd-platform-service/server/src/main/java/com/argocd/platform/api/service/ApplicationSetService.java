package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.ApplicationSetRequest;
import com.argocd.platform.api.model.response.ApplicationSetResponse;
import com.argocd.platform.api.repository.ApplicationSetRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsetsEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * CRUD service for user-defined ArgoCD ApplicationSets.
 *
 * <p><b>Architectural rule (permanent):</b> Control planes are stateless. ApplicationSets
 * are project-scoped; their CP fan-out is derived at query time via:
 * {@code project → project_clusters → clusters → control_planes}.
 *
 * <p>Partition assignment follows the same algorithm as applications (globally unique
 * partition_number, SELECT FOR UPDATE fill-first). A {@link PartitionChangedEvent} is
 * published after every mutating operation so the Redis plugin cache is evicted and
 * ArgoCD re-renders the affected Level 3 ApplicationSet within the Level 1 poll window (10 s).
 */
@Service
@RequiredArgsConstructor
public class ApplicationSetService {

    private final ApplicationSetRepository applicationSetRepository;
    private final ProjectRepository projectRepository;
    private final PartitionService partitionService;
    private final PartitionProperties partitionProperties;
    private final JsonbUtils jsonbUtils;
    private final ApplicationEventPublisher eventPublisher;

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    @Transactional
    public ApplicationSetResponse create(ApplicationSetRequest request) {
        if (StringUtils.isBlank(request.getName())) {
            throw new InvalidRequestException("ApplicationSet name is required");
        }
        if (request.getGenerators() == null || request.getGenerators().isEmpty()) {
            throw new InvalidRequestException("At least one generator is required");
        }
        if (request.getTemplate() == null || request.getTemplate().isEmpty()) {
            throw new InvalidRequestException("template is required");
        }

        UUID projectId = resolveProjectId(request);

        // Append a 5-hex-char suffix for global uniqueness — same pattern as applications.
        String finalName = request.getName() + "-" + randomSuffix();

        UUID partitionId = partitionService.resolveApplicationSetPartition(
                partitionProperties.getApplicationSetTargetSize());

        ApplicationsetsEntity entity = new ApplicationsetsEntity()
                .setName(finalName)
                .setProjectId(projectId)
                .setApplicationsetPartitionId(partitionId)
                .setGoTemplate(request.isGoTemplate())
                .setStatus(ResourceStatus.UNKNOWN.name());

        ApplicationsetsEntity saved = applicationSetRepository.save(
                entity, request.getGenerators(), request.getTemplate());

        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, partitionId, PartitionType.APPLICATION_SET));

        return toResponse(saved, request.getGenerators(), request.getTemplate(), projectId);
    }

    @Transactional
    public ApplicationSetResponse update(UUID id, ApplicationSetRequest request) {
        if (request.getGenerators() == null || request.getGenerators().isEmpty()) {
            throw new InvalidRequestException("At least one generator is required");
        }
        if (request.getTemplate() == null || request.getTemplate().isEmpty()) {
            throw new InvalidRequestException("template is required");
        }

        ApplicationsetsEntity existing = applicationSetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApplicationSet not found: " + id));

        ApplicationsetsEntity updated = applicationSetRepository.update(
                id, request.getGenerators(), request.getTemplate(), request.isGoTemplate());

        partitionService.bumpApplicationSetPartitionGeneration(
                existing.getApplicationsetPartitionId());
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, existing.getApplicationsetPartitionId(),
                        PartitionType.APPLICATION_SET));

        return toResponse(updated, request.getGenerators(), request.getTemplate(),
                existing.getProjectId());
    }

    /**
     * Returns all ApplicationSets in a single query. JSONB columns (generator_spec,
     * template_spec) are included in the entity via {@code selectFrom} — no N+1.
     */
    public List<ApplicationSetResponse> list() {
        return applicationSetRepository.findAll().stream()
                .map(e -> toResponse(e,
                        jsonbUtils.fromJsonb(e.getGeneratorSpec(), LIST_MAP_TYPE),
                        jsonbUtils.fromJsonb(e.getTemplateSpec(), MAP_TYPE),
                        e.getProjectId()))
                .collect(Collectors.toList());
    }

    public ApplicationSetResponse findById(UUID id) {
        ApplicationsetsEntity entity = applicationSetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApplicationSet not found: " + id));
        return toResponse(entity,
                jsonbUtils.fromJsonb(entity.getGeneratorSpec(), LIST_MAP_TYPE),
                jsonbUtils.fromJsonb(entity.getTemplateSpec(), MAP_TYPE),
                entity.getProjectId());
    }

    @Transactional
    public void delete(UUID id) {
        ApplicationsetsEntity existing = applicationSetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApplicationSet not found: " + id));

        UUID partitionId = existing.getApplicationsetPartitionId();
        applicationSetRepository.deleteById(id);

        partitionService.bumpApplicationSetPartitionGeneration(partitionId);
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, partitionId, PartitionType.APPLICATION_SET));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID resolveProjectId(ApplicationSetRequest request) {
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
     * Generates a 5-character lowercase hex string (same pattern as ApplicationService).
     */
    private static String randomSuffix() {
        return String.format("%05x", ThreadLocalRandom.current().nextInt(1 << 20));
    }

    private ApplicationSetResponse toResponse(ApplicationsetsEntity e,
                                              List<Map<String, Object>> generators,
                                              Map<String, Object> template,
                                              UUID projectId) {
        return ApplicationSetResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .projectId(projectId)
                .generators(generators)
                .template(template)
                .goTemplate(Boolean.TRUE.equals(e.getGoTemplate()))
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
