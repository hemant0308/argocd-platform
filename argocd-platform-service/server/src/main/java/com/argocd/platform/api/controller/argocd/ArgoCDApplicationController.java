package com.argocd.platform.api.controller.argocd;

import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.service.argocd.ArgoCDApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/argocd")
@RequiredArgsConstructor
@Validated
@Tag(name = "ArgoCD Applications", description = "Internal ArgoCD ApplicationSet plugin endpoints for applications")
public class ArgoCDApplicationController {

    private final ArgoCDApplicationService argoCDApplicationService;

    @GetMapping("/application-partitions")
    @Operation(summary = "List all application partitions",
            description = "Returns all application partitions with resource count and generation. " +
                    "Consumed by the ArgoCD ApplicationSet plugin generator.")
    public ResponseEntity<List<ApplicationPartitionResponse>> listPartitions() {
        return ResponseEntity.ok(argoCDApplicationService.listPartitions());
    }

    @GetMapping("/applications")
    @Operation(summary = "List applications by partition",
            description = "Returns all applications in the given partition with project, cluster, " +
                    "control plane, and sources. " +
                    "Supply either partitionId (UUID) or partitionNumber (integer); " +
                    "partitionId takes precedence when both are provided. " +
                    "An unrecognised value returns an empty list.")
    public ResponseEntity<List<ApplicationItem>> listByPartition(
            @Parameter(description = "Partition UUID (takes precedence over partitionNumber)")
            @RequestParam(required = false) UUID partitionId,
            @Parameter(description = "Human-readable partition number (used when partitionId is absent)")
            @RequestParam(required = false) Integer partitionNumber) {
        return ResponseEntity.ok(argoCDApplicationService.listByPartition(partitionId, partitionNumber));
    }
}
