package com.argocd.platform.api.cache.listener;

import com.argocd.platform.api.cache.PluginCacheService;
import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.service.PartitionService;
import com.argocd.platform.api.util.PartitionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts stale Redis cache entries after a partition-changing transaction commits.
 *
 * <p>Active only when {@code argocd.platform.cache.enabled=true}.
 *
 * <p>Invalidation strategy:
 * <ul>
 *   <li>When {@code partitionId} is non-null the listener evicts two keys:
 *     <ol>
 *       <li>CP-scoped group key — for {@code CLUSTER} and {@code APPLICATION} types:
 *           {@code <type>-groups:<number>:<cpName>} (e.g. {@code cluster-groups:3:cp-prod}).
 *           The key format is shared with {@link PluginCacheService#buildGroupsCacheKey}
 *           to guarantee write and eviction keys are byte-for-byte identical.
 *           For {@code PROJECT} (global): {@code project-groups:<number>} (no CP suffix).</li>
 *       <li>{@code <type>-partitions:all} — the partition list used by the top-level
 *           ApplicationSet (generation and count changed).</li>
 *     </ol>
 *   </li>
 *   <li>When {@code partitionId} is null (control-plane mutation) the entire cache
 *       is cleared because CP changes affect all partition types simultaneously.</li>
 * </ul>
 *
 * <p>If the partition number cannot be resolved (data anomaly), or if a CP-scoped
 * partition's {@code cpName} is null (should not happen under normal operation), the
 * listener falls back to a full cache clear so ArgoCD never serves permanently stale data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
public class CacheInvalidationListener {

    private final CacheManager cacheManager;
    private final PartitionService partitionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartitionChanged(PartitionChangedEvent event) {
        Cache cache = cacheManager.getCache(PluginCacheService.CACHE_NAME);
        if (cache == null) {
            log.warn("Cache '{}' not found — skipping invalidation", PluginCacheService.CACHE_NAME);
            return;
        }

        if (event.getPartitionId() == null) {
            log.info("Control-plane mutation detected — clearing entire plugin-generator cache");
            cache.clear();
            return;
        }

        partitionService.findPartitionKey(event.getPartitionType(), event.getPartitionId())
                .ifPresentOrElse(
                        pk -> evictPartitionKeys(cache, pk),
                        () -> {
                            log.warn("Could not resolve partition number for id {} (type={}) — " +
                                     "clearing entire cache as safety fallback",
                                     event.getPartitionId(), event.getPartitionType());
                            cache.clear();
                        });
    }

    private void evictPartitionKeys(Cache cache, PartitionService.PartitionKey pk) {
        String typePrefix = pk.type().name().toLowerCase(); // "cluster", "project", "application"

        // CP-scoped group resources (CLUSTER, APPLICATION) require cpName in the key to
        // prevent evicting the wrong CP's entry when two CPs share the same partition number
        // (Option B). Uses PluginCacheService.buildGroupsCacheKey so write-side and
        // eviction-side keys are always byte-for-byte identical.
        //
        // PROJECT partitions are global (no CP) — plain "project-groups:{number}" key.
        String groupKey;
        if (pk.type() == PartitionType.PROJECT) {
            groupKey = typePrefix + "-groups:" + pk.number();
        } else if (pk.cpName() != null) {
            groupKey = PluginCacheService.buildGroupsCacheKey(
                    typePrefix + "-groups",
                    String.valueOf(pk.number()),
                    pk.cpName());
        } else {
            // cpName is null for a CP-scoped partition — data anomaly.
            // The correct targeted key cannot be constructed; full-clear to be safe.
            log.warn("CP-scoped partition (type={}, number={}) has null cpName in reverse cache — " +
                     "clearing entire cache as safety fallback", pk.type(), pk.number());
            cache.clear();
            return;
        }
        cache.evict(groupKey);
        log.debug("Evicted cache key '{}'", groupKey);

        // Evict the partition list entry (generation/count changed; e.g. cluster-partitions:all)
        String partitionsKey = typePrefix + "-partitions:all";
        cache.evict(partitionsKey);
        log.debug("Evicted cache key '{}'", partitionsKey);
    }
}
