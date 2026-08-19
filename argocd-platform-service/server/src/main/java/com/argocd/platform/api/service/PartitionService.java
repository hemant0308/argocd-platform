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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Single entry point for all partition-related operations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Write path</b> ({@link #resolvePartitionId}) — delegated straight to
 *       {@link PartitionRepository} without caching.  The method uses
 *       {@code SELECT FOR UPDATE} internally and must remain transactional.</li>
 *   <li><b>Read path</b> ({@code findXxxPartitionIdByNumber}) — results are kept
 *       in a JVM-local {@link ConcurrentHashMap}.  Partition-number → UUID
 *       mappings are <em>immutable once created</em>: a partition is never
 *       renumbered or deleted, so the in-memory map is always coherent across
 *       restarts (it is simply warm vs. cold, not wrong).</li>
 *   <li><b>Reverse lookup</b> ({@link #findPartitionKey}) — used by the cache
 *       invalidation listener to translate a partition UUID back to its
 *       (type, number) pair so it can derive the exact Redis key to evict.
 *       Falls back to a DB query on cache miss.</li>
 *   <li><b>List path</b> ({@code findAllXxx}) — not cached in-memory;
 *       resource counts are dynamic.  Redis TTL handles caching at the
 *       {@link com.argocd.platform.api.cache.PluginCacheService} level.</li>
 * </ul>
 *
 * <p><b>Cache misses are never stored.</b>  If a partition number does not yet
 * exist in the DB the empty result propagates to the caller; a subsequent call
 * after the partition is created will populate the cache correctly.
 */
@Service
@RequiredArgsConstructor
public class PartitionService {

    /**
     * Immutable identifier for a (type, number) partition.
     * Exposed so that listeners can inspect the partition kind and number
     * without holding a reference to the service itself.
     */
    public record PartitionKey(PartitionType type, int number) {}

    private final PartitionRepository partitionRepository;

    /**
     * Forward cache: {@code "CLUSTER:3"} → UUID.
     * Populated on first read; never evicted (immutable relationship).
     */
    private final ConcurrentHashMap<String, UUID> forwardCache = new ConcurrentHashMap<>();

    /**
     * Reverse cache: UUID → {@link PartitionKey}.
     * Populated whenever the forward cache is populated.
     */
    private final ConcurrentHashMap<UUID, PartitionKey> reverseCache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Write path — no caching
    // -------------------------------------------------------------------------

    /**
     * Resolves (or creates) a partition for the given resource type.
     * Delegates to {@link PartitionRepository#resolvePartitionId} which uses
     * {@code SELECT FOR UPDATE} — this must always hit the DB.
     *
     * @param type       resource dimension (CLUSTER, PROJECT, APPLICATION)
     * @param targetSize max resources per partition before a new one is created
     * @return UUID of the assigned partition
     */
    public UUID resolvePartitionId(PartitionType type, int targetSize) {
        return partitionRepository.resolvePartitionId(type, targetSize);
    }

    // -------------------------------------------------------------------------
    // Read path — in-memory cached
    // -------------------------------------------------------------------------

    public Optional<UUID> findClusterPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.CLUSTER, partitionNumber,
                () -> partitionRepository.findClusterPartitionIdByNumber(partitionNumber));
    }

    public Optional<UUID> findProjectPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.PROJECT, partitionNumber,
                () -> partitionRepository.findProjectPartitionIdByNumber(partitionNumber));
    }

    public Optional<UUID> findApplicationPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.APPLICATION, partitionNumber,
                () -> partitionRepository.findApplicationPartitionIdByNumber(partitionNumber));
    }

    // -------------------------------------------------------------------------
    // Reverse lookup — used by cache invalidation listener
    // -------------------------------------------------------------------------

    /**
     * Translates a partition UUID to its {@link PartitionKey}.
     * Checks the in-memory reverse cache first; falls back to a DB query on miss
     * and populates both forward and reverse caches with the result.
     *
     * <p>Returns empty if no partition with the given id exists (data anomaly).
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

    // -------------------------------------------------------------------------
    // Write path helpers — delegate directly to repository (must be transactional)
    // -------------------------------------------------------------------------

    /**
     * Atomically bumps the application partition's {@code generation} counter
     * and returns the new value. Must be called inside the same transaction as
     * the triggering write (create/update/soft-delete/hard-delete) so the
     * generation change and the app-state change are atomic.
     *
     * <p>For hard-delete: the returned value is stored in
     * {@code applications.deletion_partition_generation} and later used by
     * the status service to race-safely confirm that the correct generation
     * was synced before advancing to {@code AWAITING_PRUNE}.
     *
     * @param partitionId UUID of the application partition to bump
     * @return new generation value after the increment
     */
    public long bumpApplicationPartitionGeneration(UUID partitionId) {
        return partitionRepository.bumpAndReturnApplicationPartitionGeneration(partitionId);
    }

    /**
     * Returns the current generation of an application partition without bumping it.
     * Used by the plugin service to include {@code generation} in the
     * {@code application-groups} response so ArgoCD carries it as a label
     * on the generated {@code application-partition-{N}-{cp}} Application.
     *
     * @param partitionId UUID of the application partition
     * @return current generation (0 if partition does not exist)
     */
    public long findApplicationPartitionGeneration(UUID partitionId) {
        return partitionRepository.findApplicationPartitionGeneration(partitionId);
    }

    // -------------------------------------------------------------------------
    // List path — not cached in-memory (counts are dynamic)
    // -------------------------------------------------------------------------

    public List<ClusterPartitionResponse> findAllClusterPartitions() {
        return partitionRepository.findAllClusterPartitions();
    }

    public List<ProjectPartitionResponse> findAllProjectPartitions() {
        return partitionRepository.findAllProjectPartitions();
    }

    public List<ApplicationPartitionResponse> findAllApplicationPartitions() {
        return partitionRepository.findAllApplicationPartitions();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Optional<UUID> findByNumber(PartitionType type, int number,
                                        Supplier<Optional<UUID>> dbQuery) {
        String key = forwardKey(type, number);
        UUID cached = forwardCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        // Cache misses are NOT stored — a not-yet-created partition number returns
        // empty every time until the partition exists in the DB.
        return dbQuery.get().map(id -> {
            forwardCache.put(key, id);
            reverseCache.put(id, new PartitionKey(type, number));
            return id;
        });
    }

    private static String forwardKey(PartitionType type, int number) {
        return type.name() + ":" + number;
    }
}
