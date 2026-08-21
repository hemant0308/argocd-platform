package com.argocd.platform.api.service;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.exception.ResourceAlreadyExistsException;
import com.argocd.platform.api.exception.ResourceNotFoundException;
import com.argocd.platform.api.model.request.FailoverRequest;
import com.argocd.platform.api.model.response.ClusterBatchItem;
import com.argocd.platform.api.model.response.FailoverResponse;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.repository.FailoverRepository;
import com.argocd.platform.api.util.FailoverClusterStatus;
import com.argocd.platform.api.util.FailoverOperationStatus;
import com.argocd.platform.api.util.SuccessCondition;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import com.argocd.platform.db.jooq.tables.pojos.FailoverOperationClustersEntity;
import com.argocd.platform.db.jooq.tables.pojos.FailoverOperationsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for the Failover Core API.
 *
 * <h3>create() happy path</h3>
 * <ol>
 *   <li>Validate at least one filter field is present.</li>
 *   <li>Resolve target control plane — 404 if unknown.</li>
 *   <li>Resolve matching clusters via the request filter — 400 if result is empty.</li>
 *   <li>Check for in-flight cluster conflicts — 409 if any cluster is already in an
 *       active (non-dry-run) operation.</li>
 *   <li>Compute batch assignments (clusters sorted by name, 1-indexed).</li>
 *   <li>Persist {@code failover_operations} row.</li>
 *   <li>Persist {@code failover_operation_clusters} rows (skipped for dry runs).</li>
 *   <li>Return {@link FailoverResponse} with the full plan preview.</li>
 * </ol>
 *
 * <h3>Dry run</h3>
 * <p>Steps 1–5 run normally. No rows are written to the database — the response is
 * ephemeral. {@code operationId} is {@code null} and {@code status} is {@code COMPLETED}.
 * The full batch preview (cluster list, batch assignments) is returned so callers can
 * inspect what would run without any side effects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailoverService {

    private final FailoverRepository failoverRepository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final FailoverBatchService failoverBatchService;

    /**
     * Creates a new failover operation (or dry-run plan) and returns its initial state.
     *
     * <p>For real runs, the operation row and cluster rows are written in a single transaction.
     * If the cluster batch insert fails, the operation row is also rolled back.
     *
     * <p>For dry runs, no rows are written. The returned {@code operationId} is {@code null}.
     *
     * @param request the validated failover request
     * @return the operation plan (real: persisted with UUID; dry-run: ephemeral, no operationId)
     * @throws InvalidRequestException        if no filter field is specified or the resolved cluster set is empty
     * @throws ResourceNotFoundException      if the target control plane does not exist
     * @throws ResourceAlreadyExistsException if any resolved cluster is already in an active operation
     */
    @Transactional
    public FailoverResponse create(FailoverRequest request) {

        // 1. Require at least one filter field
        validateHasFilter(request);

        // 2. Resolve target CP (404 if not found)
        ControlPlanesEntity targetCp = controlPlaneRepository.findByName(request.getTargetControlPlane())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Control plane not found: " + request.getTargetControlPlane()));

        // 3. Resolve matching clusters (excludes clusters already on the target CP)
        List<ClustersEntity> clusters = failoverRepository.resolveClusters(request, targetCp.getId());

        if (clusters.isEmpty()) {
            throw new InvalidRequestException(
                    "No clusters matched the specified filter, or all matching clusters are " +
                    "already assigned to control plane '" + request.getTargetControlPlane() + "'");
        }

        // 4. In-flight conflict check — 409 if any cluster is already being migrated
        List<UUID> clusterIds = clusters.stream()
                .map(ClustersEntity::getId)
                .collect(Collectors.toList());

        List<UUID> conflictIds = failoverRepository.findInflightClusterIds(clusterIds);
        if (!conflictIds.isEmpty()) {
            throw new ResourceAlreadyExistsException(
                    "The following clusters are part of an active failover operation and cannot " +
                    "be included in a new one: " + conflictIds);
        }

        // 5. Compute batch parameters
        SuccessCondition successCondition = request.getSuccessCondition() != null
                ? request.getSuccessCondition()
                : SuccessCondition.SYNCED;

        int effectiveBatchSize = (request.getBatchSize() != null)
                ? request.getBatchSize()
                : clusters.size();

        // Guard: clusters.size() > 0 is guaranteed above; effectiveBatchSize >= 1 by @Min
        int totalBatches = (int) Math.ceil((double) clusters.size() / effectiveBatchSize);

        int batchTimeoutSeconds = (request.getBatchTimeoutSeconds() != null)
                ? request.getBatchTimeoutSeconds()
                : 600;

        // 6. Resolve source CP names for the batch-item preview (one JOIN query)
        Map<UUID, String> cpNameByClusterId = failoverRepository
                .findSourceCpNamesByClusterIds(clusterIds);

        // 7. Build in-memory batch item list (source of truth for both the response
        //    and the cluster rows written to DB for real runs)
        List<ClusterBatchItem> batchItems = new ArrayList<>(clusters.size());
        List<FailoverOperationClustersEntity> clusterRows = new ArrayList<>(clusters.size());

        for (int i = 0; i < clusters.size(); i++) {
            ClustersEntity cluster = clusters.get(i);
            int batchNumber = (i / effectiveBatchSize) + 1; // 1-indexed

            batchItems.add(ClusterBatchItem.builder()
                    .clusterId(cluster.getId())
                    .clusterName(cluster.getName())
                    .sourceControlPlane(cpNameByClusterId.get(cluster.getId()))
                    .batchNumber(batchNumber)
                    // status is null for dry-run (no DB rows); PENDING for real runs
                    .status(request.isDryRun() ? null : FailoverClusterStatus.PENDING.name())
                    .build());

            if (!request.isDryRun()) {
                clusterRows.add(new FailoverOperationClustersEntity()
                        .setClusterId(cluster.getId())
                        .setBatchNumber(batchNumber)
                        .setSourceControlPlaneId(cluster.getControlPlaneId()));
                // operationId is assigned in step 10 after createOperation() returns
            }
        }

        // 8. Dry run: no DB writes — return ephemeral plan immediately
        if (request.isDryRun()) {
            int totalApplications = failoverRepository.countApplicationsForClusters(clusterIds);
            log.info("Dry-run failover: target={}, clusters={}, batches={}, applications={}",
                    request.getTargetControlPlane(), clusters.size(), totalBatches, totalApplications);
            return FailoverResponse.builder()
                    .operationId(null)
                    .status(FailoverOperationStatus.COMPLETED.name())
                    .targetControlPlane(request.getTargetControlPlane())
                    .totalClusters(clusters.size())
                    .completedClusters(0)
                    .currentBatch(0)
                    .totalBatches(totalBatches)
                    .batchSize(request.getBatchSize())
                    .successCondition(successCondition.name())
                    .dryRun(true)
                    .batchTimeoutSeconds(batchTimeoutSeconds)
                    .totalApplications(totalApplications)
                    .clusters(batchItems)
                    .build();
        }

        // 9. Persist operation row
        FailoverOperationsEntity savedOp = failoverRepository.createOperation(
                targetCp.getId(),
                clusters.size(),
                totalBatches,
                request.getBatchSize(),
                successCondition,
                false,
                batchTimeoutSeconds,
                FailoverOperationStatus.PENDING.name());

        log.info("Failover operation {} created: target={}, clusters={}, batches={}",
                savedOp.getId(), request.getTargetControlPlane(), clusters.size(), totalBatches);

        // 10. Persist cluster rows
        UUID operationId = savedOp.getId();
        clusterRows.forEach(row -> row.setOperationId(operationId));
        failoverRepository.createOperationClusters(clusterRows);

        // 11. Build response
        return FailoverResponse.builder()
                .operationId(savedOp.getId())
                .status(savedOp.getStatus())
                .targetControlPlane(request.getTargetControlPlane())
                .totalClusters(savedOp.getTotalClusters())
                .completedClusters(0)
                .currentBatch(0)
                .totalBatches(savedOp.getTotalBatches())
                .batchSize(savedOp.getBatchSize())
                .successCondition(successCondition.name())
                .dryRun(false)
                .batchTimeoutSeconds(savedOp.getBatchTimeoutSeconds())
                .clusters(batchItems)
                .createdAt(savedOp.getCreatedAt())
                .updatedAt(savedOp.getUpdatedAt())
                .build();
    }

    /**
     * Returns the current state of a failover operation by id.
     *
     * <p>Dry-run operations are not persisted and cannot be retrieved via this endpoint.
     *
     * @param id the operation UUID
     * @return the operation with live per-cluster status
     * @throws ResourceNotFoundException if no operation with the given id exists
     */
    @Transactional(readOnly = true)
    public FailoverResponse getById(UUID id) {
        FailoverOperationsEntity op = failoverRepository.findOperationById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Failover operation not found: " + id));

        ControlPlanesEntity targetCp = controlPlaneRepository.findById(op.getTargetControlPlaneId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target control plane for operation " + id + " no longer exists"));

        List<ClusterBatchItem> clusterItems = failoverRepository.findClusterBatchItems(id);

        return FailoverResponse.builder()
                .operationId(op.getId())
                .status(op.getStatus())
                .targetControlPlane(targetCp.getName())
                .totalClusters(op.getTotalClusters())
                .completedClusters(op.getCompletedClusters() != null ? op.getCompletedClusters() : 0)
                .currentBatch(op.getCurrentBatch() != null ? op.getCurrentBatch() : 0)
                .totalBatches(op.getTotalBatches())
                .batchSize(op.getBatchSize())
                .successCondition(op.getSuccessCondition())
                .dryRun(op.getDryRun() != null && op.getDryRun())
                .batchTimeoutSeconds(op.getBatchTimeoutSeconds() != null ? op.getBatchTimeoutSeconds() : 600)
                .clusters(clusterItems)
                .createdAt(op.getCreatedAt())
                .updatedAt(op.getUpdatedAt())
                .build();
    }

    // =========================================================================
    // Part 6 — Recovery APIs
    // =========================================================================

    /**
     * Cancels a failover operation that is in {@code PENDING} or
     * {@code AWAITING_BATCH_CONFIRMATION} status.
     *
     * <h3>What cancel does NOT do</h3>
     * <p>Cancel only stops future batch processing — it does <em>not</em> reverse any cluster
     * migrations that have already been applied. Clusters in {@code MIGRATED} or {@code CONFIRMED}
     * status remain on the target CP after a cancel. To reverse already-applied migrations,
     * first cancel (if still in-flight) and then call {@code /rollback}.
     *
     * <h3>Concurrency</h3>
     * <p>{@code SELECT FOR UPDATE} (without SKIP LOCKED) blocks on an active scheduler lock.
     * Once the scheduler releases the lock, the recovered state is inspected; if the operation
     * has already transitioned to a terminal status (e.g. the batch just completed while we
     * were waiting), the status check fails and a 400 is returned.
     *
     * @param id the operation UUID to cancel
     * @return the updated operation state after cancellation
     * @throws InvalidRequestException   if the operation is not found, is terminal, or is a dry-run
     */
    @Transactional
    public FailoverResponse cancel(UUID id) {
        failoverRepository.lockOperationForRecovery(
                        id, List.of(
                                FailoverOperationStatus.PENDING.name(),
                                FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name()))
                .orElseThrow(() -> new InvalidRequestException(
                        "Failover operation " + id + " cannot be cancelled: not found, " +
                        "already in a terminal state (COMPLETED/TIMED_OUT/CANCELLED/FAILED), or is a dry-run. " +
                        "Only PENDING or AWAITING_BATCH_CONFIRMATION operations can be cancelled."));

        failoverRepository.updateOperationStatus(id, FailoverOperationStatus.CANCELLED.name());

        log.info("Failover operation {}: cancelled via /cancel endpoint", id);

        // Self-invocation: @Transactional on getById() is bypassed by Spring proxy, but we are
        // already inside the cancel() transaction so the read sees the just-written CANCELLED status.
        return getById(id);
    }

    /**
     * Retries the current batch of a {@code TIMED_OUT} operation by re-stamping its
     * {@code FAILED} cluster rows as {@code MIGRATED} with a fresh timeout window.
     *
     * <p>The operation must be in {@code TIMED_OUT} status. The retry re-opens the
     * confirmation window by writing a fresh {@code migrated_at = CURRENT_TIMESTAMP},
     * resets application statuses, and bumps target partition generations to prompt ArgoCD
     * to re-emit status events. The operation transitions to {@code AWAITING_BATCH_CONFIRMATION}.
     *
     * <p>Clusters that are already {@code CONFIRMED} in the batch are not affected — only
     * {@code FAILED} rows (the ones that caused the timeout) are restamped.
     *
     * @param id the operation UUID to retry
     * @return the updated operation state after retry
     * @throws InvalidRequestException if the operation is not found, not in TIMED_OUT, or is a dry-run
     */
    @Transactional
    public FailoverResponse retry(UUID id) {
        // processRetry() is also @Transactional — it joins this outer transaction (REQUIRED propagation).
        // The FOR UPDATE lock, FK updates, and getById() read all share one transaction.
        // @TransactionalEventListener(AFTER_COMMIT) events fire after this outer transaction commits.
        boolean applied = failoverBatchService.processRetry(id);
        if (!applied) {
            throw new InvalidRequestException(
                    "Failover operation " + id + " cannot be retried: not found, " +
                    "not in TIMED_OUT status, or is a dry-run.");
        }
        return getById(id);
    }

    /**
     * Rolls back a {@code TIMED_OUT} or {@code CANCELLED} operation by reversing all
     * migrated cluster assignments.
     *
     * <p>For each cluster that was actually migrated (status {@code MIGRATED}, {@code CONFIRMED},
     * or {@code FAILED}), this method restores:
     * <ul>
     *   <li>{@code clusters.control_plane_id} → source CP (CP1)</li>
     *   <li>{@code clusters.cluster_partition_id} → source CP1 cluster partition</li>
     *   <li>{@code applications.application_partition_id} → source CP1 application partition
     *       (skipped if the cluster had no active apps at migration time)</li>
     * </ul>
     *
     * <p>Clusters in {@code PENDING} status (batches not yet started) are marked
     * {@code ROLLED_BACK} without any FK reversal since their assignments were never changed.
     *
     * <p>After rollback, application statuses are reset to UNKNOWN, partition generations are
     * bumped for all affected partitions (src + tgt), and targeted
     * {@link com.argocd.platform.api.cache.event.PartitionChangedEvent}s are published after
     * the transaction commits. The operation transitions to {@code CANCELLED} (terminal).
     *
     * @param id the operation UUID to roll back
     * @return the updated operation state after rollback
     * @throws InvalidRequestException if not found, not in TIMED_OUT or CANCELLED, or is a dry-run
     */
    @Transactional
    public FailoverResponse rollback(UUID id) {
        // processRollback() joins this outer transaction (REQUIRED propagation).
        boolean applied = failoverBatchService.processRollback(id);
        if (!applied) {
            throw new InvalidRequestException(
                    "Failover operation " + id + " cannot be rolled back: not found, " +
                    "not in TIMED_OUT or CANCELLED status, or is a dry-run. " +
                    "Only TIMED_OUT or CANCELLED operations can be rolled back.");
        }
        return getById(id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the request contains at least one filter field.
     * This prevents accidentally failing over every cluster in the platform.
     *
     * @throws InvalidRequestException if all filter fields are null or empty
     */
    private void validateHasFilter(FailoverRequest request) {
        var filter = request.getFilter();
        boolean hasFilter = filter != null && (
                (filter.getClusterIds() != null && !filter.getClusterIds().isEmpty()) ||
                (filter.getClusterNames() != null && !filter.getClusterNames().isEmpty()) ||
                (filter.getLabelSelectors() != null && !filter.getLabelSelectors().isEmpty()) ||
                (filter.getSourceControlPlanes() != null && !filter.getSourceControlPlanes().isEmpty()));

        if (!hasFilter) {
            throw new InvalidRequestException(
                    "At least one filter field must be specified inside 'filter': " +
                    "clusterIds, clusterNames, labelSelectors, or sourceControlPlanes");
        }
    }
}
