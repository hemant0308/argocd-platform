package com.argocd.platform.api.controller;

import com.argocd.platform.api.model.request.ApplicationRequest;
import com.argocd.platform.api.model.response.ApplicationResponse;
import com.argocd.platform.api.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
@Validated
@Tag(name = "Applications", description = "Onboarding and management of ArgoCD applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    @Operation(summary = "Register a new application",
            description = "Registers an application under a project and cluster. " +
                    "Partition is assigned automatically based on configured partition size.")
    public ResponseEntity<ApplicationResponse> create(@Valid @RequestBody ApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.create(request));
    }

    @GetMapping
    @Operation(summary = "List all applications")
    public ResponseEntity<List<ApplicationResponse>> list() {
        return ResponseEntity.ok(applicationService.list());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing application",
            description = "Returns 409 if deletion is already in progress for this application.")
    public ResponseEntity<ApplicationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ApplicationRequest request) {
        return ResponseEntity.ok(applicationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Initiate application deletion",
            description = """
                    Begins the async deletion state machine. Returns 202 Accepted immediately;
                    actual deletion completes when the ArgoCD on-deleted notification arrives.

                    - Soft delete (default): removes app from plugin response; ArgoCD prunes the
                      Application without cascade. No user-managed Kubernetes resources are deleted.

                    - Hard delete (?hardDelete=true): adds the resources-finalizer to the Application
                      via a two-cycle sync, then prunes with cascade. All Kubernetes resources managed
                      by this Application are deleted.

                    Returns 409 if deletion is already in progress.
                    """)
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @Parameter(description = "If true, cascade-delete all Kubernetes resources managed by this application.")
            @RequestParam(defaultValue = "false") boolean hardDelete) {
        applicationService.initiateDelete(id, hardDelete);
        return ResponseEntity.accepted().build();
    }
}
