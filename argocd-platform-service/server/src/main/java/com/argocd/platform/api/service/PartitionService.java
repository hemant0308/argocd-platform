package com.argocd.platform.api.service;

import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ClusterPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ProjectPartitionResponse;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.util.PartitionType;
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
 * <p><b>Option A — global resource-level partitions</b>: {@code cluster_partitions},
 * {@code application_partitions}, and {@code project_partitions} each have a globally
 * unique {@code partition_number}. There is no CP association on partition tables.
 *
 * <p><b>Architectural rule (permanent):</b> Control planes are stateless. Only clusters
 * have a relationship with control planes. All other resources are derived from the
 * cluster → control-plane relationship at query time.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Write path</b> — delegated to {@link PartitionRepository} without caching;
 *       the repo uses {@code SELECT FOR UPDATE} internally.</li>
 *   <li><b>Read path</b> — results kept in a JVM-local {@link ConcurrentHashMap}.
 *       All types use the key format {@code "{TYPE}:{number}"}, e.g.
 *       {@code "CLUSTER:1"}, {@code "APPLICATION:3"}, {@code "PROJECT:2"}.</li>
 *   <li><b>Reverse lookup</b> ({@link #findPartitionKey}) — translates a partition UUID
 *       back to its {@link PartitionKey} for cache invalidation; falls back to DB on miss.</li>
 *   <li><b>List path</b> — not cached in-memory; resource counts are dynamic.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PartitionService {

    /**
     * Immutable identifier for a globally-unique partition.
     *
     * <p>The cache-invalidation listener uses this to derive the exact Redis key,
     * e.g. {@code "cluster-groups:1"} or {@code "project-groups:2"}. All partition
     * types are globally scoped — there is no per-CP sub-key.
     */
    public record PartitionKey(PartitionType type, int number) {}

    private final PartitionRepository partitionRepository;

    /**
     * Forward cache: key → UUID.
     * Key format: {@code "{TYPE}:{number}"} for all partition types.
     */
    private final ConcurrentHashMap<String, UUID> forwardCache = new ConcurrentHashMap<>();

    /**
     * Reverse cache: UUID → {@link PartitionKey}.
     */
    private final ConcurrentHashMap<UUID, PartitionKey> reverseCache = new ConcurrentHashMap<>();

    // =========================================================================
    // Write path — global (all types)
    // =========================================================================

    /**
     * Resolves (or creates) a cluster partition (globally scoped).
     * Delegates to {@link PartitionRepository#resolvePartitionId} which uses
     * {@code SELECT FOR UPDATE}.
     *
     * @param targetSize max clusters per partition before a new one is created
     * @return UUID of the assigned cluster partition
     */
    public UUID resolveClusterPartition(int targetSize) {
        return partitionRepository.resolvePartitionId(PartitionType.CLUSTER, targetSize);
    }

    /**
     * Resolves (or creates) an application partition (globally scoped).
     *
     * @param targetSize max applications per partition before a new one is created
     * @return UUID of the assigned application partition
     */
    public UUID resolveApplicationPartition(int targetSize) {
        return partitionRepository.resolvePartitionId(PartitionType.APPLICATION, targetSize);
    }

    /**
     * Resolves (or creates) a project partition (globally scoped).
     * AppProjects must exist on every CP that hosts the project's clusters.
     *
     * @param targetSize max projects per partition before a new one is created
     * @return UUID of the assigned project partition
     */
    public UUID resolveProjectPartitionId(int targetSize) {
        return partitionRepository.resolvePartitionId(PartitionType.PROJECT, targetSize);
    }

    // =========================================================================
    // Read path — forward cache (number → UUID)
    // =========================================================================

    /**
     * Resolves a cluster partition UUID by its globally-unique partition number.
     * Results are cached in-memory.
     *
     * @param partitionNumber globally-unique cluster partition number
     * @return partition UUID, or empty if not yet created
     */
    public Optional<UUID> findClusterPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.CLUSTER, partitionNumber,
                () -> partitionRepository.findClusterPartitionIdByNumber(partitionNumber));
    }

    /**
     * Resolves an application partition UUID by its globally-unique partition number.
     * Results are cached in-memory.
     *
     * @param partitionNumber globally-unique application partition number
     * @return partition UUID, or empty if not yet created
     */
    public Optional<UUID> findApplicationPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.APPLICATION, partitionNumber,
                () -> partitionRepository.findApplicationPartitionIdByNumber(partitionNumber));
    }

    /**
     * Resolves a project partition UUID by its globally-unique partition number.
     * Results are cached in-memory.
     *
     * @param partitionNumber globally-unique project partition number
     * @return partition UUID, or empty if not yet created
     */
    public Optional<UUID> findProjectPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.PROJECT, partitionNumber,
                () -> partitionRepository.findProjectPartitionIdByNumber(partitionNumber));
    }

    // =========================================================================
    // Reverse lookup — used by cache invalidation listener
    // =========================================================================

    /**
     * Translates a partition UUID to its {@link PartitionKey}.
     * Checks the in-memory reverse cache first; falls back to
     * {@link PartitionRepository#findPartitionNumberById} on miss.
     *
     * <p>In Option A, all partition types are globally unique by number, so any
     * cached entry is always valid — no stale-cpName check needed.
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
                    PartitionKey pk = new PartitionKey(type, number);
                    reverseCache.put(partitionId, pk);
                    forwardCache.put(forwardKey(type, number), partitionId);
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
     * Bumps generation on a single cluster partition.
     * Called by {@code ClusterService} on update/delete so the Level 1 partition-list
     * response reflects the generation change, triggering event-driven Level 3
     * ApplicationSet reconciliation within the Level 1 poll window (10 s).
     */
    public void bumpClusterPartitionGeneration(UUID partitionId) {
        partitionRepository.bumpClusterPartitionGenerations(Set.of(partitionId));
    }

    /**
     * Bumps generation on a set of cluster partition IDs (batch).
     * Called by {@code FailoverBatchService} after migration to invalidate
     * affected cluster partition caches.
     */
    public void bumpClusterPartitionGenerations(Set<UUID> partitionIds) {
        partitionRepository.bumpClusterPartitionGenerations(partitionIds);
    }

    /**
     * Bumps generation on a set of application partition IDs (batch).
     * Called by {@code FailoverBatchService} after migration to invalidate
     * affected application partition caches.
     */
    public void bumpApplicationPartitionGenerations(Set<UUID> partitionIds) {
        partitionRepository.bumpApplicationPartitionGenerations(partitionIds);
    }

    /**
     * Bumps generation on a single project partition.
     * Called by {@code ProjectService} on update/delete so the Level 1 partition-list
     * response reflects the generation change, triggering event-driven Level 3
     * ApplicationSet reconciliation within the Level 1 poll window (10 s).
     */
    public void bumpProjectPartitionGeneration(UUID partitionId) {
        partitionRepository.bumpProjectPartitionGenerations(Set.of(partitionId));
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
     * Cache-through helper for forward (number → UUID) lookups.
     * Cache misses are NOT stored — a not-yet-created partition returns empty
     * until the partition exists in the DB.
     */
    private Optional<UUID> findByNumber(PartitionType type, int number,
                                        Supplier<Optional<UUID>> dbQuery) {
        String key = forwardKey(type, number);
        UUID cached = forwardCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return dbQuery.get().map(id -> {
            forwardCache.put(key, id);
            reverseCache.put(id, new PartitionKey(type, number));
            return id;
        });
    }

    private static String forwardKey(PartitionType type, int number) {
        // All types are globally unique: "CLUSTER:1", "APPLICATION:3", "PROJECT:2"
        return type.name() + ":" + number;
    }
}
