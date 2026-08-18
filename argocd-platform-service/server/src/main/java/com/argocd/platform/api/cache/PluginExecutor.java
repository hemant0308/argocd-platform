package com.argocd.platform.api.cache;

import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;

/**
 * Strategy interface for executing an ArgoCD ApplicationSet Plugin Generator request.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link com.argocd.platform.api.service.argocd.ArgoCDPluginService} — always present;
 *       hits the database on every call.</li>
 *   <li>{@link PluginCacheService} — active only when
 *       {@code argocd.platform.cache.enabled=true}; decorates
 *       {@code ArgoCDPluginService} with a Redis-backed cache and is marked
 *       {@code @Primary} so it takes precedence when the cache is enabled.</li>
 * </ul>
 *
 * <p>{@link com.argocd.platform.api.controller.argocd.ArgoCDPluginController} injects this
 * interface, making it transparent to the controller whether caching is active.
 */
public interface PluginExecutor {

    /**
     * Executes the plugin generator request and returns the parameter set for ArgoCD.
     *
     * @param request the ArgoCD POST body
     * @return the response to return to ArgoCD
     */
    PluginGeneratorResponse execute(PluginGeneratorRequest request);
}
