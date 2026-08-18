package com.argocd.platform.api.cache.listener;

import com.argocd.platform.api.cache.PluginCacheService;
import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.service.PartitionService;
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
 *       <li>{@code <type>-groups:<number>} — the partition-specific data used by
 *           ArgoCD per-partition ApplicationSets (e.g. {@code cluster-groups:3}).</li>
 *       <li>{@code <type>-partitions:all} — the partition list used by the top-level
 *           ApplicationSet (generation and count changed).</li>
 *     </ol>
 *   </li>
 *   <li>When {@code partitionId} is null (control-plane mutation) the entire cache
 *       is cleared because CP changes affect all partition types simultaneously.</li>
 * </ul>
 *
 * <p>If the partition number cannot be resolved (data anomaly) the listener falls
 * back to a full cache clear so ArgoCD never serves permanently stale data.
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

        // Evict the partition-specific group entry (e.g. cluster-groups:3)
        String groupKey = typePrefix + "-groups:" + pk.number();
        cache.evict(groupKey);
        log.debug("Evicted cache key '{}'", groupKey);

        // Evict the partition list entry (generation/count changed; e.g. cluster-partitions:all)
        String partitionsKey = typePrefix + "-partitions:all";
        cache.evict(partitionsKey);
        log.debug("Evicted cache key '{}'", partitionsKey);
    }
}
