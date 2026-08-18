package com.argocd.platform.api.cache;

import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import com.argocd.platform.api.service.argocd.ArgoCDPluginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Redis-backed decorator around {@link ArgoCDPluginService}.
 *
 * <p>Active only when {@code argocd.platform.cache.enabled=true}.  Marked
 * {@code @Primary} so {@link com.argocd.platform.api.controller.argocd.ArgoCDPluginController},
 * which injects the {@link PluginExecutor} interface, automatically receives this
 * implementation instead of {@code ArgoCDPluginService} when caching is on.
 *
 * <p>Cache key derivation ({@link #buildCacheKey}):
 * <pre>
 *   resource + ":" + partitionNumber   e.g. "cluster-groups:3"
 *   resource + ":all"                  e.g. "cluster-partitions:all"
 * </pre>
 * These keys are also used by
 * {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener} to evict
 * the correct entries when a partition's data changes.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
public class PluginCacheService implements PluginExecutor {

    public static final String CACHE_NAME = "plugin-generator";

    private final ArgoCDPluginService delegate;

    /**
     * Serves the request from Redis when a matching entry exists; otherwise
     * delegates to {@link ArgoCDPluginService} and caches the result.
     *
     * <p>The cache name {@value #CACHE_NAME} matches the invalidation keys used
     * by {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener}.
     */
    @Override
    @Cacheable(cacheNames = CACHE_NAME,
               key = "T(com.argocd.platform.api.cache.PluginCacheService).buildCacheKey(#request)")
    public PluginGeneratorResponse execute(PluginGeneratorRequest request) {
        log.debug("Cache miss for key '{}'; querying database", buildCacheKey(request));
        return delegate.execute(request);
    }

    /**
     * Derives a stable cache key from the plugin generator request parameters.
     *
     * <p>Must be {@code public static} for use in SpEL ({@code T(...).buildCacheKey(#request)}).
     *
     * @param request the ArgoCD POST body; tolerates null/empty gracefully
     * @return a key of the form {@code "<resource>:<partitionNumber|all>"}
     */
    public static String buildCacheKey(PluginGeneratorRequest request) {
        if (request == null
                || request.getInput() == null
                || request.getInput().getParameters() == null) {
            return "unknown:all";
        }
        Map<String, String> params = request.getInput().getParameters();
        String resource = params.getOrDefault("resource", "unknown");
        String rawPartitionNumber = params.getOrDefault("partitionNumber", "all");
        // Normalize to parsed int string so " 3" and "3" produce the same key,
        // matching the behaviour of ArgoCDPluginService.getRequiredInt() which trims before parse.
        String partitionNumber;
        try {
            partitionNumber = String.valueOf(Integer.parseInt(rawPartitionNumber.trim()));
        } catch (NumberFormatException ignored) {
            partitionNumber = rawPartitionNumber; // "all" or absent — keep as-is
        }
        return resource + ":" + partitionNumber;
    }
}
