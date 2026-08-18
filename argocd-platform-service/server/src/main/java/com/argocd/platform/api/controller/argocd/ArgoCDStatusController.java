package com.argocd.platform.api.controller.argocd;

import com.argocd.platform.api.config.NotificationProperties;
import com.argocd.platform.api.model.request.argocd.ArgoCDStatusRequest;
import com.argocd.platform.api.service.argocd.ArgoCDStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives ArgoCD notification webhook callbacks and updates resource status in the DB.
 *
 * <h3>Auth</h3>
 * <p>Expects {@code Authorization: Bearer <token>} where the token matches
 * {@code argocd.platform.notifications.token} (set via {@code NOTIFICATION_TOKEN} env var).
 * When the configured token is blank (local dev), validation is skipped.
 *
 * <h3>Callers</h3>
 * <ul>
 *   <li>Managed ArgoCD notifications controller — fires on cluster/project partition Application events</li>
 *   <li>Each CP ArgoCD notifications controller — fires on user Application events</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/argocd")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "ArgoCD Status Callbacks", description = "Internal webhook endpoint for ArgoCD notification status updates")
public class ArgoCDStatusController {

    private final ArgoCDStatusService argoCDStatusService;
    private final NotificationProperties notificationProperties;

    @PostMapping("/status")
    @Operation(
            summary = "Receive ArgoCD Application status event",
            description = "Called by ArgoCD notification webhooks when an Application sync or health status changes. " +
                    "Routes the event to the correct DB update based on the Application name pattern.")
    public ResponseEntity<Void> handleStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody ArgoCDStatusRequest request) {

        if (!notificationProperties.getToken().isBlank()) {
            String token = (authHeader != null && authHeader.startsWith("Bearer "))
                    ? authHeader.substring(7)
                    : "";
            if (!notificationProperties.getToken().equals(token)) {
                log.warn("Rejected notification status callback — invalid token for application '{}'",
                        request.getApplicationName());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        argoCDStatusService.processStatusEvent(request);
        return ResponseEntity.ok().build();
    }
}
