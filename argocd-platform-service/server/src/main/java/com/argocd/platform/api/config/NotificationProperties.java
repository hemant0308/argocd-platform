package com.argocd.platform.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the ArgoCD notification status callback endpoint.
 *
 * <p>Configured via:
 * <pre>
 * argocd:
 *   platform:
 *     notifications:
 *       token: ${NOTIFICATION_TOKEN:}
 * </pre>
 *
 * <p>The token must match the value stored in the ArgoCD notifications secret
 * ({@code platform-notification-token}) on both the managed ArgoCD and all
 * control-plane ArgoCD instances.
 *
 * <p>When {@code token} is blank (default for local dev), the
 * {@link com.argocd.platform.api.controller.argocd.ArgoCDStatusController}
 * skips token validation to allow unauthenticated local testing.
 */
@Configuration
@ConfigurationProperties(prefix = "argocd.platform.notifications")
@Data
public class NotificationProperties {

    /**
     * Shared Bearer token expected in the {@code Authorization} header of
     * {@code POST /internal/argocd/status} requests from ArgoCD notifications.
     * Blank disables token validation (local dev only).
     */
    private String token = "";
}
