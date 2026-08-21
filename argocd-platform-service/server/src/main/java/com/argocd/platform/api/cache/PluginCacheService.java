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
 *   cluster-groups:3:cp-prod     (CP-scoped group resources — Option B)
 *   application-groups:1:cp-prod (CP-scoped group resources — Option B)
 *   project-groups:2             (global project groups — no CP suffix)
 *   cluster-partitions:all       (partition list resources)
 *   project-partitions:all
 *   application-partitions:all
 * </pre>
 * The CP-scoped key format ({@code resource:partitionNumber:cpName}) prevents cache
 * collisions between two control planes that share the same partition number (Option B).
 * {@link #buildGroupsCacheKey} is package-private and shared with
 * {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener} to guarantee
 * write and eviction keys are byte-for-byte identical.
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
     * <p>For CP-scoped group resources ({@code cluster-groups}, {@code application-groups})
     * the key includes {@code cpName} to prevent cache collisions between two control planes
     * with the same partition number (Option B):
     * <pre>
     *   cluster-groups:1:cp-prod      ← this CP's partition 1
     *   cluster-groups:1:cp-staging   ← different CP, same number, different key
     * </pre>
     * The key format is also built by {@link #buildGroupsCacheKey} which is shared with
     * the invalidation listener — both sides always produce the same string.
     *
     * @param request the ArgoCD POST body; tolerates null/empty gracefully
     * @return a key of the form {@code "<resource>:<partitionNumber>"} or
     *         {@code "<resource>:<partitionNumber>:<cpName>"} for CP-scoped group resources
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

        // CP-scoped group resources require cpName in the key (Option B).
        // The name is forwarded from the cluster-partitions / application-partitions plugin
        // response via the Helm chart's cpName valuesObject field.
        if ("cluster-groups".equals(resource) || "application-groups".equals(resource)) {
            String cpName = params.getOrDefault("cpName", "").trim();
            return buildGroupsCacheKey(resource, partitionNumber, cpName);
        }

        return resource + ":" + partitionNumber;
    }

    /**
     * Builds the Redis key for a CP-scoped group resource.
     *
     * <p>Package-private so
     * {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener}
     * can use the same string format for eviction — guaranteeing the write key and the
     * eviction key are byte-for-byte identical regardless of future format changes.
     *
     * @param resource        e.g. {@code "cluster-groups"}
     * @param partitionNumber partition number as a string (trimmed, no leading zeros)
     * @param cpName          canonical CP name (trimmed); empty string is accepted but
     *                        will produce a key like {@code "cluster-groups:1:"} which
     *                        the listener treats as a full-clear trigger instead.
     * @return e.g. {@code "cluster-groups:1:cp-prod"}
     */
    public static String buildGroupsCacheKey(String resource, String partitionNumber, String cpName) {
        return resource + ":" + partitionNumber + ":" + cpName;
    }
}
