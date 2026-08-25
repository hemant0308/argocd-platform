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

import java.util.Map;

/**
 * Evicts stale Redis cache entries after a partition-changing transaction commits.
 *
 * <p>Active only when {@code argocd.platform.cache.enabled=true}.
 *
 * <p><b>Option A — global partitions:</b> All partition types (CLUSTER, APPLICATION,
 * PROJECT, APPLICATION_SET) use globally-unique partition numbers. Cache keys never
 * include a CP-name suffix. Eviction is always targeted:
 * <ul>
 *   <li>{@code <type>-groups:<number>} — e.g. {@code cluster-groups:3},
 *       {@code application-groups:1}, {@code project-groups:2},
 *       {@code applicationset-groups:1}.</li>
 *   <li>{@code <type>-partitions:all} — partition list used by the Level 1
 *       ApplicationSet (generation and resource count changed).</li>
 * </ul>
 *
 * <p><b>Cache key prefix mapping:</b>
 * {@code PartitionType.name().toLowerCase()} produces an underscore form (e.g.
 * {@code "application_set"}) which does NOT match the hyphenated resource names used
 * by {@link PluginCacheService#buildCacheKey} (e.g. {@code "applicationset-groups:1"}).
 * {@link #TYPE_PREFIX} maps each type to its exact resource-name prefix so eviction
 * and write keys are byte-for-byte identical.
 *
 * <p>When {@code partitionId} is null (control-plane mutation) the entire cache is
 * cleared because CP changes can affect the derived CP fan-out for all partitions.
 *
 * <p>If the partition number cannot be resolved (data anomaly), the listener falls
 * back to a full cache clear so ArgoCD never serves permanently stale data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
public class CacheInvalidationListener {

    /**
     * Maps each {@link PartitionType} to the resource-name prefix used in Redis cache keys.
     *
     * <p>Must match the {@code resource} parameter values understood by
     * {@link com.argocd.platform.api.service.argocd.ArgoCDPluginService} and therefore
     * the keys produced by {@link PluginCacheService#buildCacheKey}.
     *
     * <p>Example: {@code APPLICATION_SET → "applicationset"} so the evicted key is
     * {@code "applicationset-groups:3"}, matching the cache-write key for the same request.
     */
    private static final Map<PartitionType, String> TYPE_PREFIX = Map.of(
            PartitionType.CLUSTER,          "cluster",
            PartitionType.PROJECT,          "project",
            PartitionType.APPLICATION,      "application",
            PartitionType.APPLICATION_SET,  "applicationset"
    );

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
        // Use explicit prefix map — PartitionType.name().toLowerCase() produces
        // underscore form ("application_set") which differs from the hyphenated
        // resource name ("applicationset-groups") used by PluginCacheService.buildCacheKey.
        String typePrefix = TYPE_PREFIX.getOrDefault(pk.type(),
                pk.type().name().toLowerCase().replace("_", ""));

        // Option A: all partition types are globally unique by number — no CP-name suffix.
        // Key format: "<type>-groups:<number>" (e.g. "applicationset-groups:3").
        // This matches PluginCacheService.buildCacheKey() exactly.
        String groupKey = typePrefix + "-groups:" + pk.number();
        cache.evict(groupKey);
        log.debug("Evicted cache key '{}'", groupKey);

        // Evict the partition list entry (generation/count changed).
        String partitionsKey = typePrefix + "-partitions:all";
        cache.evict(partitionsKey);
        log.debug("Evicted cache key '{}'", partitionsKey);
    }
}
