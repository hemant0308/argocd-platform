package com.argocd.platform.api.service;

import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ClusterPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ProjectPartitionResponse;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.util.PartitionType;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Single entry point for all partition-related operations.
 *
 * <p><b>Option B — CP-scoped partitions</b>: cluster and application partitions are
 * per-control-plane; partition numbers are unique per-CP, not globally. Use the
 * CP-scoped write-path methods ({@link #resolveClusterPartitionForCp},
 * {@link #resolveApplicationPartitionForCp}) for cluster and application assignment.
 * Project partitions remain global.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Write path</b> — delegated to {@link PartitionRepository} without caching;
 *       the repo uses {@code SELECT FOR UPDATE} internally.</li>
 *   <li><b>Read path</b> — results kept in a JVM-local {@link ConcurrentHashMap}.
 *       For CP-scoped types the cache key includes the CP id:
 *       {@code "CLUSTER:{cpId}:{number}"}.  Project type uses the legacy
 *       {@code "PROJECT:{number}"} key (no CP).</li>
 *   <li><b>Reverse lookup</b> ({@link #findPartitionKey}) — translates a partition UUID
 *       back to its {@link PartitionKey} for cache invalidation; falls back to DB on miss.</li>
 *   <li><b>List path</b> — not cached in-memory; resource counts are dynamic.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PartitionService {

    /**
     * Immutable identifier for a partition.
     *
     * <p>For CP-scoped types ({@code CLUSTER}, {@code APPLICATION}), {@code cpId} is
     * the owning control plane's UUID. For {@code PROJECT} (global), {@code cpId} is null.
     *
     * <p>The cache-invalidation listener uses {@code cpId} to derive the exact Redis key:
     * {@code "cluster-partition:{cpName}:{number}"}.
     */
    public record PartitionKey(PartitionType type, int number, @Nullable UUID cpId) {
        /** Convenience constructor for global (project) partitions. */
        public PartitionKey(PartitionType type, int number) {
            this(type, number, null);
        }
    }

    private final PartitionRepository partitionRepository;

    /**
     * Forward cache: key → UUID.
     * Key format: {@code "CLUSTER:{cpId}:{number}"} / {@code "APPLICATION:{cpId}:{number}"}
     * for CP-scoped types; {@code "PROJECT:{number}"} for global project partitions.
     */
    private final ConcurrentHashMap<String, UUID> forwardCache = new ConcurrentHashMap<>();

    /**
     * Reverse cache: UUID → {@link PartitionKey} (includes cpId for CP-scoped types).
     */
    private final ConcurrentHashMap<UUID, PartitionKey> reverseCache = new ConcurrentHashMap<>();

    // =========================================================================
    // Write path — CP-scoped (cluster + application)
    // =========================================================================

    /**
     * Resolves (or creates) a cluster partition scoped to the given control plane.
     * Delegates to {@link PartitionRepository#resolveClusterPartitionForCp} which
     * uses {@code SELECT FOR UPDATE}.
     *
     * @param cpId       the target control plane UUID
     * @param targetSize max clusters per partition before a new one is created
     * @return UUID of the assigned CP-scoped cluster partition
     */
    public UUID resolveClusterPartitionForCp(UUID cpId, int targetSize) {
        return partitionRepository.resolveClusterPartitionForCp(cpId, targetSize);
    }

    /**
     * Resolves (or creates) an application partition scoped to the given control plane.
     * Prefer calling {@link #findApplicationPartitionForCluster} first to maintain
     * cluster-locality; fall back to this method when no partition exists yet.
     *
     * @param cpId       the target control plane UUID
     * @param targetSize max applications per partition before a new one is created
     * @return UUID of the assigned CP-scoped application partition
     */
    public UUID resolveApplicationPartitionForCp(UUID cpId, int targetSize) {
        return partitionRepository.resolveApplicationPartitionForCp(cpId, targetSize);
    }

    /**
     * Cluster-locality lookup: returns the application partition on {@code targetCpId}
     * that already holds applications from {@code clusterId}, if any.
     *
     * <p>Call this before {@link #resolveApplicationPartitionForCp} during failover batch
     * migration. An existing partition on the target CP means the cluster was already
     * partially migrated (retry case) and we reuse the same partition for consistency.
     */
    public Optional<UUID> findApplicationPartitionForCluster(UUID clusterId, UUID targetCpId) {
        return partitionRepository.findApplicationPartitionForCluster(clusterId, targetCpId);
    }

    // =========================================================================
    // Write path — project (global)
    // =========================================================================

    /**
     * Resolves (or creates) a project partition (globally scoped — no CP).
     * Project partitions are not CP-scoped; AppProjects must exist on every CP
     * that hosts the project's clusters.
     *
     * @param targetSize max projects per partition before a new one is created
     * @return UUID of the assigned project partition
     */
    public UUID resolveProjectPartitionId(int targetSize) {
        return partitionRepository.resolvePartitionId(PartitionType.PROJECT, targetSize);
    }

    // =========================================================================
    // Read path — CP-scoped (cluster + application)
    // =========================================================================

    /**
     * Resolves a cluster partition UUID by CP id and partition number.
     * Under Option B, partition numbers are unique per-CP — both cpId and number
     * are required to identify a partition.
     */
    public Optional<UUID> findClusterPartitionIdByCpAndNumber(UUID cpId, int partitionNumber) {
        return findByNumber(PartitionType.CLUSTER, cpId, partitionNumber,
                () -> partitionRepository.findClusterPartitionIdByCpAndNumber(cpId, partitionNumber));
    }

    /**
     * Resolves an application partition UUID by CP id and partition number.
     */
    public Optional<UUID> findApplicationPartitionIdByCpAndNumber(UUID cpId, int partitionNumber) {
        return findByNumber(PartitionType.APPLICATION, cpId, partitionNumber,
                () -> partitionRepository.findApplicationPartitionIdByCpAndNumber(cpId, partitionNumber));
    }

    // =========================================================================
    // Read path — project (global)
    // =========================================================================

    public Optional<UUID> findProjectPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.PROJECT, null, partitionNumber,
                () -> partitionRepository.findProjectPartitionIdByNumber(partitionNumber));
    }

    // =========================================================================
    // Reverse lookup — used by cache invalidation listener
    // =========================================================================

    /**
     * Translates a partition UUID to its {@link PartitionKey}.
     * Checks the in-memory reverse cache first; falls back to a DB query on miss
     * and populates both forward and reverse caches with the result.
     *
     * <p>For CP-scoped types the returned key contains {@code cpId}; the listener
     * uses it to build the Redis key {@code "cluster-partition:{cpName}:{number}"}.
     *
     * @return empty if no partition with the given id exists (data anomaly)
     */
    public Optional<PartitionKey> findPartitionKey(PartitionType type, UUID partitionId) {
        PartitionKey cached = reverseCache.get(partitionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return partitionRepository.findPartitionNumberById(type, partitionId)
                .map(number -> {
                    // For CP-scoped types we store cpId=null here as a sentinel — the
                    // listener must do a separate lookup for cpName from the UUID if needed.
                    // cpId will be populated on the next findClusterPartitionIdByCpAndNumber call.
                    PartitionKey pk = new PartitionKey(type, number);
                    reverseCache.put(partitionId, pk);
                    return pk;
                });
    }

    // =========================================================================
    // Generation helpers
    // =========================================================================

    /**
     * Atomically bumps the application partition's {@code generation} counter
     * and returns the new value. Must be called inside the same transaction as
     * the triggering write so the generation change and the app-state change are atomic.
     *
     * <p>For hard-delete: the returned value is stored in
     * {@code applications.deletion_partition_generation} and later used by
     * the status service to race-safely confirm that the correct generation
     * was synced before advancing to {@code AWAITING_PRUNE}.
     */
    public long bumpApplicationPartitionGeneration(UUID partitionId) {
        return partitionRepository.bumpAndReturnApplicationPartitionGeneration(partitionId);
    }

    /**
     * Returns the current generation of an application partition without bumping it.
     * Used by the plugin service to include {@code generation} in the
     * {@code application-groups} response.
     */
    public long findApplicationPartitionGeneration(UUID partitionId) {
        return partitionRepository.findApplicationPartitionGeneration(partitionId);
    }

    /**
     * Bumps generation on a set of cluster partition IDs (batch).
     * Called by {@code FailoverBatchService} after migration to invalidate
     * source and target cluster partition caches.
     */
    public void bumpClusterPartitionGenerations(Set<UUID> partitionIds) {
        partitionRepository.bumpClusterPartitionGenerations(partitionIds);
    }

    /**
     * Bumps generation on a set of application partition IDs (batch).
     * Called by {@code FailoverBatchService} after migration to invalidate
     * source and target application partition caches.
     */
    public void bumpApplicationPartitionGenerations(Set<UUID> partitionIds) {
        partitionRepository.bumpApplicationPartitionGenerations(partitionIds);
    }

    // =========================================================================
    // List path — not cached in-memory (resource counts are dynamic)
    // =========================================================================

    public List<ClusterPartitionResponse> findAllClusterPartitions() {
        return partitionRepository.findAllClusterPartitions();
    }

    public List<ProjectPartitionResponse> findAllProjectPartitions() {
        return partitionRepository.findAllProjectPartitions();
    }

    public List<ApplicationPartitionResponse> findAllApplicationPartitions() {
        return partitionRepository.findAllApplicationPartitions();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * @param cpId null for global (project) partitions; non-null for CP-scoped types
     */
    private Optional<UUID> findByNumber(PartitionType type, @Nullable UUID cpId,
                                        int number, Supplier<Optional<UUID>> dbQuery) {
        String key = forwardKey(type, cpId, number);
        UUID cached = forwardCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        // Cache misses are NOT stored — a not-yet-created partition returns empty until
        // the partition exists in the DB.
        return dbQuery.get().map(id -> {
            forwardCache.put(key, id);
            reverseCache.put(id, new PartitionKey(type, number, cpId));
            return id;
        });
    }

    private static String forwardKey(PartitionType type, @Nullable UUID cpId, int number) {
        if (cpId != null) {
            // CP-scoped: "CLUSTER:{cpId}:{number}"
            return type.name() + ":" + cpId + ":" + number;
        }
        // Global (project): "PROJECT:{number}"
        return type.name() + ":" + number;
    }
}
