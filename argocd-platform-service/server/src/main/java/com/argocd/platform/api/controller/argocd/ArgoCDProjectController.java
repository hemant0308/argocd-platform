package com.argocd.platform.api.controller.argocd;

import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.model.response.argocd.ProjectPartitionResponse;
import com.argocd.platform.api.service.argocd.ArgoCDProjectService;
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
@Tag(name = "ArgoCD Projects", description = "Internal ArgoCD ApplicationSet plugin endpoints for projects")
public class ArgoCDProjectController {

    private final ArgoCDProjectService argoCDProjectService;

    @GetMapping("/project-partitions")
    @Operation(summary = "List all project partitions",
            description = "Returns all project partitions with resource count and generation. " +
                    "Consumed by the ArgoCD ApplicationSet plugin generator.")
    public ResponseEntity<List<ProjectPartitionResponse>> listPartitions() {
        return ResponseEntity.ok(argoCDProjectService.listPartitions());
    }

    @GetMapping("/projects")
    @Operation(summary = "List projects by partition",
            description = "Returns all projects assigned to the given partition. " +
                    "Supply either partitionId (UUID) or partitionNumber (integer); " +
                    "partitionId takes precedence when both are provided. " +
                    "An unrecognised value returns an empty list.")
    public ResponseEntity<List<ProjectItem>> listByPartition(
            @Parameter(description = "Partition UUID (takes precedence over partitionNumber)")
            @RequestParam(required = false) UUID partitionId,
            @Parameter(description = "Human-readable partition number (used when partitionId is absent)")
            @RequestParam(required = false) Integer partitionNumber) {
        return ResponseEntity.ok(argoCDProjectService.listByPartition(partitionId, partitionNumber));
    }
}
