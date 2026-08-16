package com.argocd.platform.api.controller;

import com.argocd.platform.api.model.request.ControlPlaneRequest;
import com.argocd.platform.api.model.response.ControlPlaneResponse;
import com.argocd.platform.api.service.ControlPlaneService;
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
@RequestMapping("/api/v1/control-planes")
@RequiredArgsConstructor
@Validated
@Tag(name = "Control Planes", description = "Onboarding and management of ArgoCD control plane instances")
public class ControlPlaneController {

    private final ControlPlaneService controlPlaneService;

    @PostMapping
    @Operation(summary = "Register a new control plane")
    public ResponseEntity<ControlPlaneResponse> create(@Valid @RequestBody ControlPlaneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(controlPlaneService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing control plane")
    public ResponseEntity<ControlPlaneResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ControlPlaneRequest request) {
        return ResponseEntity.ok(controlPlaneService.update(id, request));
    }
}
