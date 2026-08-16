package com.argocd.platform.api.controller.argocd;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Placeholder for future ArgoCD event callback endpoints.
 *
 * <p>Intended to receive status notifications from ArgoCD (e.g. application sync status,
 * health transitions) so the platform can update resource state reactively rather than
 * polling. No routes are defined yet.
 */
@RestController
@RequestMapping("/internal/argocd")
@RequiredArgsConstructor
@Validated
@Tag(name = "ArgoCD Events", description = "Internal ArgoCD event callback endpoints (not yet implemented)")
public class ArgoCDEventController {
    // Routes will be added in a future iteration
}
