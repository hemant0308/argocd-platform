package com.argocd.platform.api.controller;

import com.argocd.platform.api.model.request.FailoverRequest;
import com.argocd.platform.api.model.response.FailoverResponse;
import com.argocd.platform.api.service.FailoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints for the Failover Core API.
 *
 * <ul>
 *   <li>{@code POST /api/v1/failover}              — create a new operation (or dry-run plan).</li>
 *   <li>{@code GET  /api/v1/failover/{id}}          — retrieve an operation's current state.</li>
 *   <li>{@code POST /api/v1/failover/{id}/cancel}   — cancel a PENDING or AWAITING operation.</li>
 *   <li>{@code POST /api/v1/failover/{id}/retry}    — retry the current batch of a TIMED_OUT operation.</li>
 *   <li>{@code POST /api/v1/failover/{id}/rollback} — reverse all migrated cluster assignments.</li>
 * </ul>
 *
 * <h3>Recovery endpoint semantics</h3>
 * <ul>
 *   <li><b>cancel</b>: stops future batch processing; already-migrated clusters remain on the
 *       target CP. Use /rollback afterwards to reverse them if needed.</li>
 *   <li><b>retry</b>: re-opens the confirmation window for the current batch by re-stamping
 *       {@code FAILED} cluster rows as {@code MIGRATED} with a fresh timeout anchor. Application
 *       statuses are reset and partition generations are bumped to trigger ArgoCD re-evaluation.</li>
 *   <li><b>rollback</b>: reverses source CP and partition FK assignments for all migrated
 *       clusters using the pre-migration values stored in {@code failover_operation_clusters}.
 *       Valid from {@code TIMED_OUT} or {@code CANCELLED} status.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/failover")
@RequiredArgsConstructor
@Validated
@Tag(name = "Failover", description = "Controlled cluster failover between control planes")
public class FailoverController {

    private final FailoverService failoverService;

    /**
     * Creates a new failover operation.
     *
     * <p>The request body specifies a filter (which clusters to move), the target control
     * plane, batch configuration, and success condition. The service resolves the filter,
     * validates there are no in-flight conflicts, persists the operation, and returns
     * the full plan preview.
     *
     * <p>Set {@code dryRun = true} to preview the plan without changing any cluster
     * assignments. The response will contain the resolved cluster list and batch
     * assignments but no migration will be performed.
     *
     * @param request the failover request; all filter, batch, and target fields
     * @return {@code 201 Created} with the new operation's initial state
     */
    @PostMapping
    @Operation(
            summary = "Create a failover operation",
            description = "Resolves cluster filter, validates conflicts, and starts a controlled " +
                          "cluster migration to the target control plane. Use dryRun=true to " +
                          "preview the plan without making changes.")
    public ResponseEntity<FailoverResponse> create(@Valid @RequestBody FailoverRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(failoverService.create(request));
    }

    /**
     * Returns the current state of a failover operation.
     *
     * <p>The response includes live status, batch progress counters, and per-cluster
     * migration state. For dry-run operations the {@code clusters} list is empty.
     *
     * @param id the operation UUID
     * @return {@code 200 OK} with the operation state, or {@code 404} if not found
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get a failover operation",
            description = "Returns the live state of a failover operation including per-cluster " +
                          "migration progress. Returns 404 if the operation does not exist.")
    public ResponseEntity<FailoverResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(failoverService.getById(id));
    }

    // =========================================================================
    // Part 6 — Recovery endpoints
    // =========================================================================

    /**
     * Cancels a failover operation that is in {@code PENDING} or
     * {@code AWAITING_BATCH_CONFIRMATION} status.
     *
     * <p>Future batch processing stops immediately. Already-migrated clusters are
     * <strong>not</strong> reversed — use {@code /rollback} for that.
     *
     * @param id the operation UUID
     * @return {@code 200 OK} with the updated operation state, or {@code 400} if the operation
     *         is in a terminal state or does not exist
     */
    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel a failover operation",
            description = "Stops future batch processing on a PENDING or AWAITING_BATCH_CONFIRMATION " +
                          "operation. Already-migrated clusters are NOT reversed — call /rollback " +
                          "afterwards to reverse them. Returns 400 if the operation is already terminal.")
    public ResponseEntity<FailoverResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(failoverService.cancel(id));
    }

    /**
     * Retries the current batch of a {@code TIMED_OUT} failover operation.
     *
     * <p>Re-stamps {@code FAILED} cluster rows as {@code MIGRATED} with a fresh timeout
     * window, resets application statuses, and bumps partition generations to prompt
     * ArgoCD to re-emit status events. Only {@code FAILED} rows in the current batch are
     * affected — already-{@code CONFIRMED} rows are untouched.
     *
     * @param id the operation UUID
     * @return {@code 200 OK} with the updated operation state (now AWAITING_BATCH_CONFIRMATION),
     *         or {@code 400} if the operation is not in TIMED_OUT status
     */
    @PostMapping("/{id}/retry")
    @Operation(
            summary = "Retry a timed-out failover operation",
            description = "Re-opens the confirmation window for the current batch by restamping FAILED " +
                          "cluster rows as MIGRATED with a fresh timeout. Only valid from TIMED_OUT status.")
    public ResponseEntity<FailoverResponse> retry(@PathVariable UUID id) {
        return ResponseEntity.ok(failoverService.retry(id));
    }

    /**
     * Rolls back a {@code TIMED_OUT} or {@code CANCELLED} failover operation by reversing
     * all migrated cluster assignments.
     *
     * <p>For each cluster that was migrated (status {@code MIGRATED}, {@code CONFIRMED}, or
     * {@code FAILED}), restores {@code clusters.control_plane_id},
     * {@code clusters.cluster_partition_id}, and {@code applications.application_partition_id}
     * to their pre-migration values stored in {@code failover_operation_clusters}. Application
     * statuses are reset, partition generations are bumped, and targeted cache events are
     * emitted. The operation transitions to {@code CANCELLED} (terminal).
     *
     * @param id the operation UUID
     * @return {@code 200 OK} with the updated operation state (now CANCELLED),
     *         or {@code 400} if the operation is not in TIMED_OUT or CANCELLED status
     */
    @PostMapping("/{id}/rollback")
    @Operation(
            summary = "Roll back a failover operation",
            description = "Reverses all migrated cluster assignments by restoring source CP and " +
                          "partition FKs. Valid from TIMED_OUT or CANCELLED status. " +
                          "Operation transitions to CANCELLED after rollback.")
    public ResponseEntity<FailoverResponse> rollback(@PathVariable UUID id) {
        return ResponseEntity.ok(failoverService.rollback(id));
    }
}
