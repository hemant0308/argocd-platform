package com.argocd.platform.api.service;

import com.argocd.platform.api.assignment.ControlPlaneResolver;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.assignment.ControlPlaneAssignmentAlgorithm;
import com.argocd.platform.api.model.auth.ClusterAuth;
import com.argocd.platform.api.model.request.ClusterRequest;
import com.argocd.platform.api.model.response.ClusterResponse;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ClusterService {

    private final ClusterRepository clusterRepository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final PartitionRepository partitionRepository;
    private final PartitionProperties partitionProperties;
    private final ObjectMapper objectMapper;

    /**
     * Strategy map built once at startup from all {@link ControlPlaneResolver} beans.
     * Adding a new algorithm requires only a new {@code @Component} — no switch edits here.
     */
    private final Map<ControlPlaneAssignmentAlgorithm, ControlPlaneResolver> resolverMap;

    public ClusterService(
            ClusterRepository clusterRepository,
            ControlPlaneRepository controlPlaneRepository,
            PartitionRepository partitionRepository,
            PartitionProperties partitionProperties,
            ObjectMapper objectMapper,
            List<ControlPlaneResolver> resolvers) {
        this.clusterRepository = clusterRepository;
        this.controlPlaneRepository = controlPlaneRepository;
        this.partitionRepository = partitionRepository;
        this.partitionProperties = partitionProperties;
        this.objectMapper = objectMapper;
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
        // Always resolve control plane on create — reassignControlPlane is update-only
        UUID controlPlaneId = resolveControlPlaneId(request);

        UUID partitionId = partitionRepository.resolvePartitionId(
                PartitionType.CLUSTER, partitionProperties.getClusterTargetSize());

        ClustersEntity entity = new ClustersEntity()
                .setName(request.getName())
                .setServer(request.getServer())
                .setControlPlaneId(controlPlaneId)
                .setClusterPartitionId(partitionId)
                .setStatus(ResourceStatus.UNKNOWN.name())
                .setNamespaces(toJsonb(request.getNamespaces()))
                .setLabels(toJsonb(request.getLabels()))
                .setAuth(toJsonb(request.getAuth()));

        ClustersEntity saved = clusterRepository.save(entity);
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

        // Partition assignment is never changed via the API
        existing.setName(request.getName())
                .setServer(request.getServer())
                .setControlPlaneId(controlPlaneId)
                .setNamespaces(toJsonb(request.getNamespaces()))
                .setLabels(toJsonb(request.getLabels()))
                .setAuth(toJsonb(request.getAuth()));

        ClustersEntity updated = clusterRepository.update(id, existing);
        return toResponse(updated, fetchControlPlaneName(controlPlaneId));
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
                .namespaces(fromJsonb(e.getNamespaces(), new TypeReference<List<String>>() {}))
                .labels(fromJsonb(e.getLabels(), new TypeReference<Map<String, String>>() {}))
                .auth(fromJsonb(e.getAuth(), new TypeReference<ClusterAuth>() {}))
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private String fetchControlPlaneName(UUID controlPlaneId) {
        return controlPlaneRepository.findById(controlPlaneId)
                .map(ControlPlanesEntity::getName)
                .orElse(null);
    }

    /**
     * Serializes an arbitrary object to a jOOQ {@link JSONB} value.
     * Returns {@code null} when {@code value} is {@code null}.
     *
     * @throws IllegalStateException if Jackson serialization fails (should never happen
     *                               for {@code List<String>} or {@code Map<String,String>})
     */
    private JSONB toJsonb(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSONB.jsonb(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value to JSONB: " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes a jOOQ {@link JSONB} column value into the target type.
     * Returns {@code null} when {@code jsonb} is {@code null} or its data is blank.
     * Logs a warning and returns {@code null} if the stored JSON cannot be parsed.
     */
    private <T> T fromJsonb(JSONB jsonb, TypeReference<T> typeRef) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonb.data(), typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSONB column value '{}': {}", jsonb.data(), e.getMessage());
            return null;
        }
    }
}
