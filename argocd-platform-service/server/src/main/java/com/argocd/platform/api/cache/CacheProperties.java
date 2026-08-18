package com.argocd.platform.api.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Redis-backed plugin generator cache.
 *
 * <p>Activated when {@code argocd.platform.cache.enabled=true} (env: {@code CACHE_ENABLED}).
 * Registered via {@code @EnableConfigurationProperties} in {@link CacheConfig}.
 *
 * <pre>
 * argocd:
 *   platform:
 *     cache:
 *       enabled: true
 *       ttl-minutes: 5
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "argocd.platform.cache")
public class CacheProperties {

    /**
     * Master switch for the Redis caching layer.
     * When {@code false} (default), {@link CacheConfig}, {@link PluginCacheService},
     * and all cache listeners are not registered.
     */
    private boolean enabled = false;

    /**
     * Time-to-live for plugin generator cache entries in minutes.
     * Acts as a safety net: even without an explicit invalidation event,
     * ArgoCD will receive fresh data within this window.
     */
    private int ttlMinutes = 5;
}
