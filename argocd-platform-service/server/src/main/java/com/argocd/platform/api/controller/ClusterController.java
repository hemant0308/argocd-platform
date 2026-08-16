package com.argocd.platform.api.controller;

import com.argocd.platform.api.model.request.ClusterRequest;
import com.argocd.platform.api.model.response.ClusterResponse;
import com.argocd.platform.api.service.ClusterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
@Validated
@Tag(name = "Clusters", description = "Onboarding and management of user Kubernetes clusters")
public class ClusterController {

    private final ClusterService clusterService;

    @PostMapping
    @Operation(summary = "Register a new cluster",
            description = "Registers a cluster under a control plane. Partition is assigned automatically.")
    public ResponseEntity<ClusterResponse> create(@Valid @RequestBody ClusterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clusterService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing cluster")
    public ResponseEntity<ClusterResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ClusterRequest request) {
        return ResponseEntity.ok(clusterService.update(id, request));
    }
}
