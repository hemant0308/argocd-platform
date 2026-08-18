package com.argocd.platform.api.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration — active only when {@code argocd.platform.cache.enabled=true}.
 *
 * <p>When the property is false (default), this class is not instantiated, so:
 * <ul>
 *   <li>{@code @EnableCaching} is not applied — {@code @Cacheable} annotations are no-ops.</li>
 *   <li>No {@link RedisCacheManager} bean is created — no Redis connection is attempted.</li>
 *   <li>{@link CacheProperties} is not registered — safe for local dev without Redis.</li>
 * </ul>
 *
 * <p>Value serialization uses {@link GenericJackson2JsonRedisSerializer}, which embeds the
 * fully-qualified class name in the JSON so that deserialization works without type hints.
 * All cached response DTOs must have a no-arg constructor (Lombok {@code @NoArgsConstructor}).
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * Primary {@link RedisCacheManager}.
     *
     * <ul>
     *   <li>Keys are plain strings (resource:partitionNumber).</li>
     *   <li>Values are JSON with embedded type information.</li>
     *   <li>Null values are not cached — callers receive a fresh DB result instead.</li>
     *   <li>TTL is applied globally as a safety net; explicit eviction via
     *       {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener}
     *       handles event-driven invalidation.</li>
     * </ul>
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          CacheProperties cacheProperties) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(cacheProperties.getTtlMinutes()))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
