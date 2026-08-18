package com.argocd.platform.api.service;

import com.argocd.platform.api.assignment.ControlPlaneResolver;
import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceAlreadyExistsException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.model.request.ClusterRequest;
import com.argocd.platform.api.model.response.ClusterResponse;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClusterService {

    private final ClusterRepository clusterRepository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final PartitionService partitionService;
    private final PartitionProperties partitionProperties;
    private final JsonbUtils jsonbUtils;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Strategy map built once at startup from all {@link ControlPlaneResolver} beans.
     * Adding a new algorithm requires only a new {@code @Component} — no switch edits here.
     */
    private final Map<ControlPlaneAssignmentAlgorithm, ControlPlaneResolver> resolverMap;

    public ClusterService(
            ClusterRepository clusterRepository,
            ControlPlaneRepository controlPlaneRepository,
            PartitionService partitionService,
            PartitionProperties partitionProperties,
            JsonbUtils jsonbUtils,
            ApplicationEventPublisher eventPublisher,
            List<ControlPlaneResolver> resolvers) {
        this.clusterRepository = clusterRepository;
        this.controlPlaneRepository = controlPlaneRepository;
        this.partitionService = partitionService;
        this.partitionProperties = partitionProperties;
        this.jsonbUtils = jsonbUtils;
        this.eventPublisher = eventPublisher;
        this.resolverMap = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ControlPlaneResolver::supportedAlgorithm,
                        r -> r));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Transactional
    public ClusterResponse create(ClusterRequest request) {
        // Reject duplicates before hitting the DB unique constraint
        if (clusterRepository.findByName(request.getName()).isPresent()) {
            throw new ResourceAlreadyExistsException(
                    "Cluster with name '" + request.getName() + "' already exists.");
        }

        // Always resolve control plane on create — reassignControlPlane is update-only
        UUID controlPlaneId = resolveControlPlaneId(request);

        UUID partitionId = partitionService.resolvePartitionId(
                PartitionType.CLUSTER, partitionProperties.getClusterTargetSize());

        ClustersEntity entity = new ClustersEntity()
                .setName(request.getName())
                .setServer(request.getServer())
                .setControlPlaneId(controlPlaneId)
                .setClusterPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name())
                .setNamespaces(jsonbUtils.toJsonb(request.getNamespaces()))
                .setLabels(jsonbUtils.toJsonb(request.getLabels()))
                .setAuth(jsonbUtils.toJsonb(request.getAuth()));

        ClustersEntity saved = clusterRepository.save(entity);
        eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.CLUSTER));
        return toResponse(saved, fetchControlPlaneName(controlPlaneId));
    }

    @Transactional
    public ClusterResponse update(UUID id, ClusterRequest request) {
        ClustersEntity existing = clusterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cluster not found: " + id));

        // Control plane is only reassigned when explicitly requested; otherwise preserved.
        UUID controlPlaneId = request.isReassignControlPlane()
                ? resolveControlPlaneId(request)
                : existing.getControlPlaneId();

        // Partition assignment and name are never changed via the API
        existing.setServer(request.getServer())
                .setControlPlaneId(controlPlaneId)
                .setNamespaces(jsonbUtils.toJsonb(request.getNamespaces()))
                .setLabels(jsonbUtils.toJsonb(request.getLabels()))
                .setAuth(jsonbUtils.toJsonb(request.getAuth()));

        ClustersEntity updated = clusterRepository.update(id, existing);
        eventPublisher.publishEvent(
                new PartitionChangedEvent(this, existing.getClusterPartitionId(), PartitionType.CLUSTER));
        return toResponse(updated, fetchControlPlaneName(controlPlaneId));
    }

    /**
     * Returns all clusters ordered by name, each enriched with its control-plane name.
     */
    public List<ClusterResponse> list() {
        List<ClustersEntity> clusters = clusterRepository.findAll();
        Map<UUID, String> cpNames = controlPlaneRepository.findAll().stream()
                .collect(Collectors.toMap(
                        ControlPlanesEntity::getId,
                        ControlPlanesEntity::getName,
                        (a, b) -> a));
        return clusters.stream()
                .map(c -> toResponse(c, cpNames.get(c.getControlPlaneId())))
                .collect(Collectors.toList());
    }

    /**
     * Deletes a cluster by id.
     * If the cluster is still referenced by applications a 409 is returned
     * (FK violation caught by {@link com.argocd.platform.api.exception.GlobalExceptionHandler}).
     */
    @Transactional
    public void delete(UUID id) {
        ClustersEntity existing = clusterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cluster not found: " + id));
        UUID partitionId = existing.getClusterPartitionId();
        clusterRepository.deleteById(id);
        eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.CLUSTER));
    }

    // -------------------------------------------------------------------------
    // Control plane resolution
    // -------------------------------------------------------------------------

    /**
     * Determines the effective {@link ControlPlaneAssignmentAlgorithm} for the request
     * and delegates to the corresponding {@link ControlPlaneResolver}.
     *
     * <p>Algorithm derivation when {@code assignmentAlgorithm} is absent:
     * <ul>
     *   <li>{@code controlPlane} is non-blank → {@link ControlPlaneAssignmentAlgorithm#EXPLICIT}</li>
     *   <li>otherwise → {@link ControlPlaneAssignmentAlgorithm#CONSISTENT_HASH}</li>
     * </ul>
     *
     * <p>Cross-field validation: {@code EXPLICIT} requires a non-blank {@code controlPlane}.
     */
    private UUID resolveControlPlaneId(ClusterRequest request) {
        ControlPlaneAssignmentAlgorithm algorithm = effectiveAlgorithm(request);

        if (algorithm == ControlPlaneAssignmentAlgorithm.EXPLICIT
                && (request.getControlPlane() == null || request.getControlPlane().isBlank())) {
            throw new InvalidRequestException(
                    "controlPlane name must not be blank when assignmentAlgorithm is EXPLICIT");
        }

        ControlPlaneResolver resolver = resolverMap.get(algorithm);
        if (resolver == null) {
            throw new InvalidRequestException(
                    "No control-plane resolver registered for algorithm: " + algorithm);
        }

        return resolver.resolve(request);
    }

    /**
     * Returns the explicitly set algorithm, or derives a sensible default so callers
     * never need to specify {@code assignmentAlgorithm} when the intent is obvious.
     */
    private ControlPlaneAssignmentAlgorithm effectiveAlgorithm(ClusterRequest request) {
        if (request.getAssignmentAlgorithm() != null) {
            return request.getAssignmentAlgorithm();
        }
        boolean hasExplicitName = request.getControlPlane() != null
                && !request.getControlPlane().isBlank();
        return hasExplicitName
                ? ControlPlaneAssignmentAlgorithm.EXPLICIT
                : ControlPlaneAssignmentAlgorithm.CONSISTENT_HASH;
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private ClusterResponse toResponse(ClustersEntity e, String controlPlaneName) {
        return ClusterResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .server(e.getServer())
                .status(e.getStatus())
                .controlPlaneId(e.getControlPlaneId())
                .controlPlaneName(controlPlaneName)
                .namespaces(jsonbUtils.fromJsonb(e.getNamespaces(), new TypeReference<List<String>>() {}))
                .labels(jsonbUtils.fromJsonb(e.getLabels(), new TypeReference<Map<String, String>>() {}))
                .auth(jsonbUtils.fromJsonb(e.getAuth(), new TypeReference<Map<String, Object>>() {}))
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private String fetchControlPlaneName(UUID controlPlaneId) {
        return controlPlaneRepository.findById(controlPlaneId)
                .map(ControlPlanesEntity::getName)
                .orElse(null);
    }

}
