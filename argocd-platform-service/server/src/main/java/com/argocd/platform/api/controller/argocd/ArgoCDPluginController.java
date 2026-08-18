package com.argocd.platform.api.controller.argocd;

import com.argocd.platform.api.cache.PluginExecutor;
import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ArgoCD ApplicationSet Plugin Generator endpoint.
 *
 * <p>ArgoCD POSTs to exactly {@code /api/v1/getparams.execute} — this path is
 * hard-coded in the ArgoCD ApplicationSet Plugin Generator protocol and cannot
 * be changed. The plugin ConfigMap (argocd-platform-plugin) must point to this
 * service's base URL without any path suffix.
 *
 * <p>Dispatch is by {@code input.parameters.resource}:
 * <ul>
 *   <li>{@code cluster-partitions} — list all cluster partitions</li>
 *   <li>{@code cluster-groups} — clusters in a partition grouped by control plane
 *       (requires {@code partitionNumber})</li>
 *   <li>{@code project-partitions} — list all project partitions with control planes</li>
 *   <li>{@code projects} — flat project list for a partition
 *       (requires {@code partitionNumber})</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "ArgoCD Plugin Generator",
        description = "Single ApplicationSet Plugin Generator endpoint; dispatches by resource parameter")
public class ArgoCDPluginController {

    private final PluginExecutor pluginService;

    @PostMapping("/getparams.execute")
    @Operation(
            summary = "Plugin Generator execute",
            description = "ArgoCD ApplicationSet Plugin Generator protocol endpoint. " +
                    "POSTed by ArgoCD for every ApplicationSet reconcile cycle. " +
                    "Set input.parameters.resource to select the data source. " +
                    "All response values are strings per the ArgoCD protocol.")
    public ResponseEntity<PluginGeneratorResponse> execute(
            @RequestBody PluginGeneratorRequest request) {
        return ResponseEntity.ok(pluginService.execute(request));
    }
}
