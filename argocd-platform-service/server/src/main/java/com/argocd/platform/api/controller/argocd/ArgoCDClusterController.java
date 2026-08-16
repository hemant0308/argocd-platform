package com.argocd.platform.api.controller.argocd;

import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.model.response.argocd.ClusterPartitionResponse;
import com.argocd.platform.api.service.argocd.ArgoCDClusterService;
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
@Tag(name = "ArgoCD Clusters", description = "Internal ArgoCD ApplicationSet plugin endpoints for clusters")
public class ArgoCDClusterController {

    private final ArgoCDClusterService argoCDClusterService;

    @GetMapping("/cluster-partitions")
    @Operation(summary = "List all cluster partitions",
            description = "Returns all cluster partitions with resource count and generation. " +
                    "Consumed by the ArgoCD ApplicationSet plugin generator.")
    public ResponseEntity<List<ClusterPartitionResponse>> listPartitions() {
        return ResponseEntity.ok(argoCDClusterService.listPartitions());
    }

    @GetMapping("/clusters")
    @Operation(summary = "List clusters by partition",
            description = "Returns all clusters assigned to the given partition. " +
                    "Supply either partitionId (UUID) or partitionNumber (integer); " +
                    "partitionId takes precedence when both are provided. " +
                    "An unrecognised value returns an empty list.")
    public ResponseEntity<List<ClusterItem>> listByPartition(
            @Parameter(description = "Partition UUID (takes precedence over partitionNumber)")
            @RequestParam(required = false) UUID partitionId,
            @Parameter(description = "Human-readable partition number (used when partitionId is absent)")
            @RequestParam(required = false) Integer partitionNumber) {
        return ResponseEntity.ok(argoCDClusterService.listByPartition(partitionId, partitionNumber));
    }
}
