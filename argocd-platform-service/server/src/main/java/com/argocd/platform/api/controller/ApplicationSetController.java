package com.argocd.platform.api.controller;

import com.argocd.platform.api.model.request.ApplicationSetRequest;
import com.argocd.platform.api.model.response.ApplicationSetResponse;
import com.argocd.platform.api.service.ApplicationSetService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applicationsets")
@RequiredArgsConstructor
@Validated
@Tag(name = "ApplicationSets", description = "CRUD for user-defined ArgoCD ApplicationSets")
public class ApplicationSetController {

    private final ApplicationSetService applicationSetService;

    @PostMapping
    @Operation(summary = "Create a new user-defined ApplicationSet",
            description = "Registers an ApplicationSet under a project. " +
                    "A 5-hex-char suffix is appended to the name for global uniqueness. " +
                    "The ApplicationSet is deployed to every control plane that hosts a " +
                    "cluster belonging to the project (fan-out via project_clusters).")
    public ResponseEntity<ApplicationSetResponse> create(
            @Valid @RequestBody ApplicationSetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(applicationSetService.create(request));
    }

    @GetMapping
    @Operation(summary = "List all ApplicationSets")
    public ResponseEntity<List<ApplicationSetResponse>> list() {
        return ResponseEntity.ok(applicationSetService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an ApplicationSet by id")
    public ResponseEntity<ApplicationSetResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(applicationSetService.findById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing ApplicationSet",
            description = "Updates generators, template, and goTemplate. " +
                    "Name and project are immutable after creation.")
    public ResponseEntity<ApplicationSetResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ApplicationSetRequest request) {
        return ResponseEntity.ok(applicationSetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an ApplicationSet",
            description = "Hard deletes the ApplicationSet from the platform DB and " +
                    "publishes a cache invalidation event. ArgoCD prunes the ApplicationSet " +
                    "from all target control planes on its next plugin poll (≤10 s via Level 1).")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        applicationSetService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
