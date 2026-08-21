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
     * the owning control plane's UUID and {@code cpName} is its canonical name
     * (matches {@code CONTROL_PLANES.NAME} and the Helm/plugin {@code cpName} parameter).
     * For {@code PROJECT} (global), both {@code cpId} and {@code cpName} are null.
     *
     * <p>The cache-invalidation listener uses {@code cpName} to derive the exact Redis key,
     * e.g. {@code "cluster-groups:1:cp-prod"}.  Both fields are populated on the
     * read path; only {@code cpId} is populated on the forward-cache path (the reverse cache
     * always has {@code cpName} populated after the first call to either
     * {@link #findClusterPartitionIdByCpAndNumber} or {@link #findPartitionKey}).
     */
    public record PartitionKey(PartitionType type, int number, @Nullable UUID cpId,
                               @Nullable String cpName) {
        /** Convenience constructor for global (project) partitions. */
        public PartitionKey(PartitionType type, int number) {
            this(type, number, null, null);
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
     * Resolves a cluster partition UUID by CP id, partition number, and CP name.
     * Under Option B, partition numbers are unique per-CP — both cpId and number
     * are required to identify a partition.
     *
     * <p>{@code cpName} is threaded through to the reverse-cache entry so the
     * {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener} can build
     * the correct eviction key ({@code cluster-groups:{number}:{cpName}}) without an
     * extra DB round-trip.
     *
     * @param cpId          owning control plane UUID
     * @param partitionNumber partition number (unique within the CP)
     * @param cpName        canonical CP name — must match {@code CONTROL_PLANES.NAME}
     */
    public Optional<UUID> findClusterPartitionIdByCpAndNumber(UUID cpId, int partitionNumber,
                                                              String cpName) {
        return findByNumber(PartitionType.CLUSTER, cpId, cpName, partitionNumber,
                () -> partitionRepository.findClusterPartitionIdByCpAndNumber(cpId, partitionNumber));
    }

    /**
     * Resolves an application partition UUID by CP id, partition number, and CP name.
     *
     * @param cpId          owning control plane UUID
     * @param partitionNumber partition number (unique within the CP)
     * @param cpName        canonical CP name — must match {@code CONTROL_PLANES.NAME}
     */
    public Optional<UUID> findApplicationPartitionIdByCpAndNumber(UUID cpId, int partitionNumber,
                                                                  String cpName) {
        return findByNumber(PartitionType.APPLICATION, cpId, cpName, partitionNumber,
                () -> partitionRepository.findApplicationPartitionIdByCpAndNumber(cpId, partitionNumber));
    }

    // =========================================================================
    // Read path — project (global)
    // =========================================================================

    public Optional<UUID> findProjectPartitionIdByNumber(int partitionNumber) {
        return findByNumber(PartitionType.PROJECT, null, null, partitionNumber,
                () -> partitionRepository.findProjectPartitionIdByNumber(partitionNumber));
    }

    // =========================================================================
    // Reverse lookup — used by cache invalidation listener
    // =========================================================================

    /**
     * Translates a partition UUID to its {@link PartitionKey}.
     * Checks the in-memory reverse cache first; falls back to
     * {@link PartitionRepository#findPartitionInfoById} on miss (or when a cached entry
     * has {@code cpName=null} for a CP-scoped type — a stale entry written before
     * {@code cpName} was threaded through the forward-cache path).
     *
     * <p>For CP-scoped types the returned key always contains a non-null {@code cpName}.
     * The cache-invalidation listener uses it to build the correct Redis eviction key,
     * e.g. {@code "cluster-groups:1:cp-prod"}.
     *
     * @return empty if no partition with the given id exists (data anomaly)
     */
    public Optional<PartitionKey> findPartitionKey(PartitionType type, UUID partitionId) {
        PartitionKey cached = reverseCache.get(partitionId);
        // For CP-scoped types a cached entry with cpName=null is not usable by the
        // invalidation listener — fall through to the DB to get the canonical name.
        // Project partitions never need cpName, so a non-null cached entry is always valid.
        if (cached != null && (type == PartitionType.PROJECT || cached.cpName() != null)) {
            return Optional.of(cached);
        }
        return partitionRepository.findPartitionInfoById(type, partitionId)
                .map(info -> {
                    // cpId is intentionally left null here: this path is invoked by the
                    // cache-invalidation listener which only needs cpName for key construction.
                    // cpId is populated via the forward-cache path
                    // (findClusterPartitionIdByCpAndNumber / findApplicationPartitionIdByCpAndNumber).
                    PartitionKey pk = new PartitionKey(type, info.number(), null, info.cpName());
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
     * @param cpId   null for global (project) partitions; non-null for CP-scoped types
     * @param cpName canonical control plane name; null for PROJECT partitions.
     *               Stored in the reverse-cache entry so the invalidation listener can
     *               build the CP-scoped Redis key without an extra DB round-trip.
     *               If the entry already exists with a non-null cpName it is left unchanged.
     */
    private Optional<UUID> findByNumber(PartitionType type, @Nullable UUID cpId,
                                        @Nullable String cpName,
                                        int number, Supplier<Optional<UUID>> dbQuery) {
        String key = forwardKey(type, cpId, number);
        UUID cached = forwardCache.get(key);
        if (cached != null) {
            // Re-stamp cpName in the reverse cache if the existing entry lacks it.
            // This handles the case where the partition was cached before cpName was
            // threaded through (e.g. warm-up ordering differences).
            reverseCache.compute(cached, (id, existing) ->
                    (existing != null && existing.cpName() != null)
                            ? existing
                            : new PartitionKey(type, number, cpId, cpName));
            return Optional.of(cached);
        }
        // Cache misses are NOT stored — a not-yet-created partition returns empty until
        // the partition exists in the DB.
        return dbQuery.get().map(id -> {
            forwardCache.put(key, id);
            reverseCache.put(id, new PartitionKey(type, number, cpId, cpName));
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
