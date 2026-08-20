package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.config.PartitionProperties;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.FailoverRepository;
import com.argocd.platform.api.util.FailoverOperationStatus;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.SuccessCondition;
import com.argocd.platform.db.jooq.tables.pojos.FailoverOperationsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transactional processor for individual failover batch operations.
 *
 * <p>This service is a companion to {@link com.argocd.platform.api.task.FailoverBatchScheduler}.
 * The scheduler discovers candidate operation IDs (without holding locks) and delegates
 * each one here. Each method opens its own transaction, acquires a
 * {@code SELECT FOR UPDATE SKIP LOCKED} row lock, performs work, and commits — keeping
 * the lock window as short as possible and isolating failures to a single operation.
 *
 * <h3>Concurrency model</h3>
 * <p>Because the lock is acquired inside the transaction rather than before it, multiple
 * scheduler instances can run concurrently without blocking each other:
 * <ul>
 *   <li>Instance A acquires the lock on operation X — instance B's {@code SKIP LOCKED}
 *       query silently skips X and returns empty, so B does nothing for X.</li>
 *   <li>If A crashes mid-transaction, the lock is released on rollback and B will pick
 *       up X on the next scheduler tick.</li>
 * </ul>
 *
 * <h3>Timestamp gate</h3>
 * <p>The migration step resets {@code applications.sync_status / health_status} to
 * {@code UNKNOWN} and sets {@code failover_operation_clusters.migrated_at} — both using
 * {@code CURRENT_TIMESTAMP} (DB-side) in the same transaction. Postgres evaluates
 * {@code CURRENT_TIMESTAMP} once per transaction, so both timestamps are equal. The
 * confirmation gate {@code applications.updated_at > migrated_at} is therefore
 * {@code false} for the reset, and only becomes {@code true} when a real ArgoCD status
 * event — arriving in a later transaction — bumps {@code applications.updated_at}.
 *
 * <h3>CP-scoped partition migration (Option B)</h3>
 * <p>{@link #migrateBatch} now updates both {@code clusters.control_plane_id} and
 * {@code clusters.cluster_partition_id} atomically, and reassigns
 * {@code applications.application_partition_id} per cluster. Targeted
 * {@link PartitionChangedEvent}s (one per unique affected partition) replace the previous
 * full-cache-clear null/null event, minimising cache eviction blast radius.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailoverBatchService {

    private final FailoverRepository failoverRepository;
    private final ApplicationRepository applicationRepository;
    private final PartitionService partitionService;
    private final PartitionProperties partitionProperties;
    private final ApplicationEventPublisher eventPublisher;

    // =========================================================================
    // PENDING → AWAITING_BATCH_CONFIRMATION
    // =========================================================================

    /**
     * Processes a single failover operation that is in {@code PENDING} status.
     *
     * <p>Acquires a {@code SELECT FOR UPDATE SKIP LOCKED} row lock on the operation.
     * If the lock cannot be acquired (operation processed elsewhere, status changed,
     * or another instance has the lock), this method returns silently.
     *
     * <p>On success:
     * <ol>
     *   <li>Migrates batch 1 clusters: updates {@code clusters.control_plane_id} and
     *       {@code cluster_partition_id} (CP-scoped Option B), reassigns
     *       {@code application_partition_id}, resets application statuses, stamps
     *       {@code migrated_at}.</li>
     *   <li>Transitions the operation to {@code AWAITING_BATCH_CONFIRMATION} with
     *       {@code current_batch = 1}.</li>
     *   <li>Publishes targeted {@link PartitionChangedEvent}s (after commit) to flush
     *       only the affected partition caches in the ApplicationSet plugin response.</li>
     * </ol>
     *
     * @param operationId the operation UUID to process
     */
    @Transactional
    public void processPendingOperation(UUID operationId) {
        Optional<FailoverOperationsEntity> locked = failoverRepository.lockOperationById(
                operationId, FailoverOperationStatus.PENDING.name());

        if (locked.isEmpty()) {
            log.debug("Failover scheduler: PENDING operation {} not available (already processed or locked)",
                    operationId);
            return;
        }

        FailoverOperationsEntity op = locked.get();
        int completedClusters = op.getCompletedClusters() != null ? op.getCompletedClusters() : 0;

        log.info("Failover operation {}: starting batch 1 of {} (target CP: {})",
                op.getId(), op.getTotalBatches(), op.getTargetControlPlaneId());

        migrateBatch(op, 1);

        failoverRepository.updateOperationProgress(
                op.getId(),
                FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name(),
                1,
                completedClusters);
    }

    // =========================================================================
    // AWAITING_BATCH_CONFIRMATION → AWAITING_BATCH_CONFIRMATION / COMPLETED / TIMED_OUT
    // =========================================================================

    /**
     * Processes a single failover operation that is in {@code AWAITING_BATCH_CONFIRMATION} status.
     *
     * <p>Acquires a {@code SELECT FOR UPDATE SKIP LOCKED} row lock on the operation.
     * Returns silently if the lock is unavailable.
     *
     * <p>On each invocation the method evaluates one of three outcomes for the current batch:
     * <ol>
     *   <li><strong>Timeout</strong>: {@code now() > MAX(migrated_at) + batch_timeout_seconds}
     *       — marks all {@code MIGRATED} cluster rows as {@code FAILED} and transitions the
     *       operation to {@code TIMED_OUT}. Already-applied cluster migrations are not rolled
     *       back (use {@code /rollback} recovery API for that).</li>
     *   <li><strong>Confirmed</strong>: all {@code MIGRATED} clusters satisfy the success
     *       condition (every active app has {@code updated_at > migrated_at} AND the status
     *       field satisfies the condition).
     *       <ul>
     *         <li>If this is the last batch: transitions to {@code COMPLETED}.</li>
     *         <li>Otherwise: migrates the next batch and transitions back to
     *             {@code AWAITING_BATCH_CONFIRMATION} with the incremented {@code current_batch}.</li>
     *       </ul>
     *   </li>
     *   <li><strong>Still waiting</strong>: some clusters are not yet confirmed, not timed out
     *       — does nothing (will be evaluated again on the next scheduler tick).</li>
     * </ol>
     *
     * @param operationId the operation UUID to evaluate
     */
    @Transactional
    public void processAwaitingOperation(UUID operationId) {
        Optional<FailoverOperationsEntity> locked = failoverRepository.lockOperationById(
                operationId, FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name());

        if (locked.isEmpty()) {
            log.debug("Failover scheduler: AWAITING operation {} not available (already processed or locked)",
                    operationId);
            return;
        }

        FailoverOperationsEntity op = locked.get();
        int currentBatch      = op.getCurrentBatch()       != null ? op.getCurrentBatch()       : 1;
        int completedClusters = op.getCompletedClusters()  != null ? op.getCompletedClusters()  : 0;
        int batchTimeout      = op.getBatchTimeoutSeconds() != null ? op.getBatchTimeoutSeconds() : 600;

        // Timeout anchor: MAX(migrated_at) for current batch
        Optional<LocalDateTime> migratedAtOpt = failoverRepository.getBatchMigratedAt(
                op.getId(), currentBatch);

        if (migratedAtOpt.isEmpty()) {
            // No MIGRATED rows — defensive guard; shouldn't happen in normal flow
            log.warn("Failover operation {}: batch {} has no MIGRATED rows — skipping poll",
                    op.getId(), currentBatch);
            return;
        }

        LocalDateTime migratedAt = migratedAtOpt.get();

        // Check timeout
        if (LocalDateTime.now().isAfter(migratedAt.plusSeconds(batchTimeout))) {
            log.warn("Failover operation {}: batch {} timed out after {} s (migrated_at={})",
                    op.getId(), currentBatch, batchTimeout, migratedAt);
            failoverRepository.failBatch(op.getId(), currentBatch);
            failoverRepository.updateOperationProgress(
                    op.getId(),
                    FailoverOperationStatus.TIMED_OUT.name(),
                    currentBatch,
                    completedClusters);
            return;
        }

        // Confirmation guard: migratedCount > 0 AND unconfirmedCount == 0
        int migratedCount = failoverRepository.countMigratedClusters(op.getId(), currentBatch);
        if (migratedCount == 0) {
            log.debug("Failover operation {}: batch {} — no MIGRATED rows yet, skipping",
                    op.getId(), currentBatch);
            return;
        }

        SuccessCondition sc = SuccessCondition.valueOf(op.getSuccessCondition());
        int unconfirmedCount = failoverRepository.countUnconfirmedMigratedClusters(
                op.getId(), currentBatch, sc);

        if (unconfirmedCount > 0) {
            log.debug("Failover operation {}: batch {} — {}/{} clusters confirmed (condition={})",
                    op.getId(), currentBatch, (migratedCount - unconfirmedCount), migratedCount, sc);
            return; // Check again on next poll
        }

        // All clusters confirmed
        int confirmed = failoverRepository.confirmBatch(op.getId(), currentBatch);
        int newCompletedClusters = completedClusters + confirmed;

        log.info("Failover operation {}: batch {} confirmed ({} clusters, condition={})",
                op.getId(), currentBatch, confirmed, sc);

        if (currentBatch >= op.getTotalBatches()) {
            // All batches done → COMPLETED
            failoverRepository.updateOperationProgress(
                    op.getId(),
                    FailoverOperationStatus.COMPLETED.name(),
                    currentBatch,
                    newCompletedClusters);
            log.info("Failover operation {}: COMPLETED — {} total clusters migrated and confirmed",
                    op.getId(), newCompletedClusters);
        } else {
            // Advance to next batch: migrate it and stay in AWAITING_BATCH_CONFIRMATION
            int nextBatch = currentBatch + 1;
            log.info("Failover operation {}: advancing to batch {} of {}",
                    op.getId(), nextBatch, op.getTotalBatches());
            migrateBatch(op, nextBatch);
            failoverRepository.updateOperationProgress(
                    op.getId(),
                    FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name(),
                    nextBatch,
                    newCompletedClusters);
        }
    }

    // =========================================================================
    // Part 6 — Recovery APIs
    // =========================================================================

    /**
     * Retry: transitions a {@code TIMED_OUT} operation back to
     * {@code AWAITING_BATCH_CONFIRMATION} by re-stamping {@code FAILED} cluster rows.
     *
     * <h3>Why re-stamp {@code migrated_at}?</h3>
     * <p>The confirmation poller uses {@code MAX(migrated_at) + batchTimeoutSeconds} as its
     * timeout anchor. If we only flip the status without updating {@code migrated_at}, the
     * anchor is still the original migration timestamp — the poller would immediately re-timeout
     * on the very next tick. Re-stamping with {@code CURRENT_TIMESTAMP} opens a fresh
     * confirmation window from "now".
     *
     * <h3>Why reset application statuses?</h3>
     * <p>The confirmation gate requires {@code applications.updated_at > migrated_at}. After
     * re-stamping, the new {@code migrated_at} is later than any existing {@code updated_at}.
     * Resetting statuses to UNKNOWN ensures the gate stays closed until a real ArgoCD status
     * event (on the already-migrated CP2 partition) bumps {@code updated_at}.
     *
     * <h3>Why bump generations?</h3>
     * <p>Generation bumps on the target CP2 partitions trigger ArgoCD to re-evaluate the
     * ApplicationSet, causing it to re-compute desired state and emit fresh status events.
     * Without a bump, ArgoCD may not re-emit a status notification for an already-synced
     * application, leaving the confirmation gate permanently closed for that app.
     *
     * @param operationId the operation UUID to retry
     * @return {@code true} if the retry was applied; {@code false} if the operation was not
     *         found, was not in {@code TIMED_OUT} status, or is a dry-run
     */
    @Transactional
    public boolean processRetry(UUID operationId) {
        Optional<FailoverOperationsEntity> locked = failoverRepository.lockOperationForRecovery(
                operationId, List.of(FailoverOperationStatus.TIMED_OUT.name()));

        if (locked.isEmpty()) {
            log.debug("Failover retry: operation {} not available (not found, wrong status, or dry-run)",
                    operationId);
            return false;
        }

        FailoverOperationsEntity op = locked.get();
        int currentBatch = op.getCurrentBatch() != null ? op.getCurrentBatch() : 1;

        // Restamp FAILED → MIGRATED with a fresh timeout anchor
        List<UUID> retriedClusterIds = failoverRepository.restampFailedClustersAsMigrated(op.getId(), currentBatch);

        if (retriedClusterIds.isEmpty()) {
            // Guard: no FAILED rows — still transition back to AWAITING so the poller retries
            log.warn("Failover operation {}: retry called but no FAILED cluster rows found in batch {} " +
                     "— transitioning to AWAITING_BATCH_CONFIRMATION without restamping",
                    op.getId(), currentBatch);
        } else {
            log.info("Failover operation {}: retry — restamping {} cluster(s) in batch {} FAILED→MIGRATED " +
                     "with fresh migrated_at",
                    op.getId(), retriedClusterIds.size(), currentBatch);

            // Reset app statuses: new migrated_at > existing updated_at → gate stays closed until
            // a real ArgoCD event bumps updated_at on the target CP2 partition
            applicationRepository.resetSyncAndHealthStatusForClusters(retriedClusterIds);

            // Collect target partition IDs from recorded tracking data
            List<FailoverRepository.PartitionTracking> tracking =
                    failoverRepository.findPartitionTrackingForClusters(op.getId(), retriedClusterIds);

            Set<UUID> clusterPartitions = new LinkedHashSet<>();
            Set<UUID> appPartitions = new LinkedHashSet<>();
            for (FailoverRepository.PartitionTracking t : tracking) {
                if (t.targetClusterPartitionId() != null) {
                    clusterPartitions.add(t.targetClusterPartitionId());
                }
                if (t.targetApplicationPartitionId() != null) {
                    appPartitions.add(t.targetApplicationPartitionId());
                }
            }

            // Bump generations → triggers ArgoCD to re-emit status notifications
            if (!clusterPartitions.isEmpty()) {
                partitionService.bumpClusterPartitionGenerations(clusterPartitions);
            }
            if (!appPartitions.isEmpty()) {
                partitionService.bumpApplicationPartitionGenerations(appPartitions);
            }

            log.info("Failover operation {}: retry — bumped {} cluster partition(s), {} app partition(s)",
                    op.getId(), clusterPartitions.size(), appPartitions.size());

            // Targeted cache events (after commit via @TransactionalEventListener)
            for (UUID pid : clusterPartitions) {
                eventPublisher.publishEvent(new PartitionChangedEvent(this, pid, PartitionType.CLUSTER));
            }
            for (UUID pid : appPartitions) {
                eventPublisher.publishEvent(new PartitionChangedEvent(this, pid, PartitionType.APPLICATION));
            }
        }

        failoverRepository.updateOperationStatus(
                op.getId(), FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name());
        return true;
    }

    /**
     * Rollback: reverses all migrated cluster assignments for a {@code TIMED_OUT} or
     * {@code CANCELLED} operation, restoring source CP and partition FKs.
     *
     * <h3>What is reversed</h3>
     * <p>Only clusters in {@code MIGRATED}, {@code CONFIRMED}, or {@code FAILED} status
     * actually had their {@code control_plane_id} and partition FKs changed. For each:
     * <ul>
     *   <li>{@code clusters.control_plane_id} → {@code sourceControlPlaneId}</li>
     *   <li>{@code clusters.cluster_partition_id} → {@code sourceClusterPartitionId}</li>
     *   <li>{@code applications.application_partition_id} → {@code sourceApplicationPartitionId}
     *       (skipped if null — cluster had no active apps at migration time)</li>
     * </ul>
     *
     * <p>{@code PENDING} clusters (batches not yet started) are marked {@code ROLLED_BACK}
     * but their FKs are not touched — they were never changed.
     *
     * <h3>After rollback</h3>
     * <p>Application statuses are reset to UNKNOWN for reverted clusters so that the CP1
     * ArgoCD re-evaluates their state and emits fresh status events. Partition generations are
     * bumped for all source and target partitions, and targeted {@link PartitionChangedEvent}s
     * are published after commit. The operation transitions to {@code CANCELLED} (terminal).
     *
     * @param operationId the operation UUID to roll back
     * @return {@code true} if rollback was applied; {@code false} if not found, wrong status,
     *         or dry-run
     */
    @Transactional
    public boolean processRollback(UUID operationId) {
        Optional<FailoverOperationsEntity> locked = failoverRepository.lockOperationForRecovery(
                operationId, List.of(
                        FailoverOperationStatus.TIMED_OUT.name(),
                        FailoverOperationStatus.CANCELLED.name()));

        if (locked.isEmpty()) {
            log.debug("Failover rollback: operation {} not available " +
                      "(not found, wrong status, or dry-run)", operationId);
            return false;
        }

        FailoverOperationsEntity op = locked.get();

        // Only MIGRATED/CONFIRMED/FAILED rows actually had their FKs changed
        List<FailoverRepository.RollbackTarget> targets = failoverRepository.findRollbackTargets(op.getId());

        if (targets.isEmpty()) {
            log.info("Failover operation {}: rollback — no migrated clusters to reverse " +
                     "(all batches were PENDING). Marking operation CANCELLED.", op.getId());
            // Mark all PENDING cluster rows as ROLLED_BACK — operation is terminal
            List<UUID> allClusterIds = failoverRepository.getClusterIdsForOperation(op.getId());
            failoverRepository.markClustersRolledBack(op.getId(), allClusterIds);
            failoverRepository.updateOperationStatus(op.getId(), FailoverOperationStatus.CANCELLED.name());
            return true;
        }

        log.info("Failover operation {}: rollback — reversing {} migrated cluster(s) to source CP",
                op.getId(), targets.size());

        Set<UUID> clusterPartitionsToInvalidate = new LinkedHashSet<>();
        Set<UUID> appPartitionsToInvalidate     = new LinkedHashSet<>();
        List<UUID> rolledBackClusterIds          = new ArrayList<>();
        List<UUID> appsResetClusterIds           = new ArrayList<>();

        for (FailoverRepository.RollbackTarget t : targets) {
            // Restore cluster FK (control_plane_id + cluster_partition_id)
            if (t.sourceClusterPartitionId() != null && t.sourceControlPlaneId() != null) {
                failoverRepository.restoreClusterToSource(
                        t.clusterId(), t.sourceControlPlaneId(), t.sourceClusterPartitionId());
                // Both src and tgt need generation bumps: src is coming back online, tgt is losing a cluster
                clusterPartitionsToInvalidate.add(t.sourceClusterPartitionId());
                if (t.targetClusterPartitionId() != null) {
                    clusterPartitionsToInvalidate.add(t.targetClusterPartitionId());
                }
            }

            // Restore application_partition_id (only if cluster had apps at migration time)
            if (t.sourceApplicationPartitionId() != null) {
                failoverRepository.restoreApplicationPartitionToSource(
                        t.clusterId(), t.sourceApplicationPartitionId());
                appsResetClusterIds.add(t.clusterId());
                appPartitionsToInvalidate.add(t.sourceApplicationPartitionId());
                if (t.targetApplicationPartitionId() != null) {
                    appPartitionsToInvalidate.add(t.targetApplicationPartitionId());
                }
            }

            rolledBackClusterIds.add(t.clusterId());
        }

        // Mark migrated rows as ROLLED_BACK
        failoverRepository.markClustersRolledBack(op.getId(), rolledBackClusterIds);

        // Also mark any still-PENDING rows (future batches) as ROLLED_BACK — operation is terminal
        List<UUID> allClusterIds = failoverRepository.getClusterIdsForOperation(op.getId());
        List<UUID> pendingClusterIds = allClusterIds.stream()
                .filter(id -> !rolledBackClusterIds.contains(id))
                .collect(Collectors.toList());
        if (!pendingClusterIds.isEmpty()) {
            failoverRepository.markClustersRolledBack(op.getId(), pendingClusterIds);
        }

        // Reset app statuses for reverted clusters: they're back on CP1; ArgoCD will re-evaluate
        if (!appsResetClusterIds.isEmpty()) {
            applicationRepository.resetSyncAndHealthStatusForClusters(appsResetClusterIds);
        }

        // Bump partition generations for all affected partitions
        if (!clusterPartitionsToInvalidate.isEmpty()) {
            partitionService.bumpClusterPartitionGenerations(clusterPartitionsToInvalidate);
        }
        if (!appPartitionsToInvalidate.isEmpty()) {
            partitionService.bumpApplicationPartitionGenerations(appPartitionsToInvalidate);
        }

        // Transition operation to terminal CANCELLED status
        failoverRepository.updateOperationStatus(op.getId(), FailoverOperationStatus.CANCELLED.name());

        log.info("Failover operation {}: rollback complete — {} cluster(s) restored, " +
                 "{} pending cluster(s) marked ROLLED_BACK, " +
                 "cluster_partitions_invalidated={}, app_partitions_invalidated={}",
                op.getId(), rolledBackClusterIds.size(), pendingClusterIds.size(),
                clusterPartitionsToInvalidate.size(), appPartitionsToInvalidate.size());

        // Targeted cache events (deferred via @TransactionalEventListener AFTER_COMMIT)
        for (UUID pid : clusterPartitionsToInvalidate) {
            eventPublisher.publishEvent(new PartitionChangedEvent(this, pid, PartitionType.CLUSTER));
        }
        for (UUID pid : appPartitionsToInvalidate) {
            eventPublisher.publishEvent(new PartitionChangedEvent(this, pid, PartitionType.APPLICATION));
        }

        return true;
    }

    // =========================================================================
    // Private — batch migration (Option B, 10-step flow)
    // =========================================================================

    /**
     * Migrates a single batch of clusters from their source CP to the operation's target CP,
     * assigning CP-scoped partitions (Option B) and emitting targeted cache invalidation events.
     *
     * <ol>
     *   <li><b>Deletion guard</b>: query for in-flight HARD_DELETE apps and emit a WARNING.
     *       Migration continues regardless — apps are NOT excluded from partition reassignment.
     *       See {@code DeletionStateTransitionTask.fallbackHardDeleteTimeout()} for the
     *       deletion race window explanation and comments.</li>
     *   <li><b>Resolve target cluster partition</b>: find or create a CP2-scoped cluster
     *       partition with available capacity. One partition is resolved for the whole batch
     *       (all batch clusters land together, preserving cluster-locality at the partition level).</li>
     *   <li><b>Per-cluster: capture source IDs</b>: read the current
     *       {@code cluster_partition_id} and {@code application_partition_id} before any write,
     *       so that {@code /rollback} has the information to reverse the migration.</li>
     *   <li><b>Per-cluster: resolve target app partition</b>: prefer an existing CP2 partition
     *       already holding apps from this cluster (idempotency on retry), else resolve greedily.</li>
     *   <li><b>Record partition IDs</b>: write source/target partition UUIDs to
     *       {@code failover_operation_clusters} before any migration runs.</li>
     *   <li><b>Migrate clusters</b>: atomic UPDATE sets both {@code control_plane_id} and
     *       {@code cluster_partition_id} for all batch clusters.</li>
     *   <li><b>Migrate app partitions</b>: per-cluster UPDATE of
     *       {@code applications.application_partition_id}. All apps are moved,
     *       including those in HARD_DELETE (warn-only — see step 1).</li>
     *   <li><b>Bump generations</b>: increment generation on every unique source/target
     *       cluster partition and application partition (signals desired-state change to ArgoCD).</li>
     *   <li><b>Reset app statuses</b>: resets sync/health to UNKNOWN. Uses the same
     *       {@code CURRENT_TIMESTAMP} as {@code migrated_at}, so the timestamp gate is
     *       {@code false} until a real ArgoCD event arrives.</li>
     *   <li><b>Mark MIGRATED</b>: stamps {@code migrated_at} and {@code status = MIGRATED}
     *       on {@code failover_operation_clusters} rows.</li>
     *   <li><b>Targeted cache events</b>: one {@link PartitionChangedEvent} per unique affected
     *       partition (replaces the previous null/null full-clear broadcast).</li>
     * </ol>
     *
     * <p>Called from within an existing {@code @Transactional} method — all DB operations
     * share the outer transaction. {@link PartitionChangedEvent}s are deferred via
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} and fire only on commit.
     *
     * @param op          the locked operation entity (provides target CP id and operation id)
     * @param batchNumber the 1-indexed batch to migrate
     */
    private void migrateBatch(FailoverOperationsEntity op, int batchNumber) {
        List<UUID> clusterIds = failoverRepository.getClusterIdsForBatch(op.getId(), batchNumber);

        if (clusterIds.isEmpty()) {
            log.warn("Failover operation {}: batch {} has no cluster rows — nothing to migrate",
                    op.getId(), batchNumber);
            return;
        }

        UUID targetCpId = op.getTargetControlPlaneId();

        // --- Step 1: Deletion guard (warn only — do NOT skip or block migration) ---
        List<FailoverRepository.InflightHardDeleteApp> inflightDeletes =
                failoverRepository.findInflightHardDeleteAppsForClusters(clusterIds);
        if (!inflightDeletes.isEmpty()) {
            String appNames = inflightDeletes.stream()
                    .map(a -> a.appName() + " [cluster=" + a.clusterId() + ", partition=" + a.currentAppPartitionId() + "]")
                    .collect(Collectors.joining(", "));
            log.warn("Failover operation {}: batch {} — {} application(s) in HARD_DELETE state detected. " +
                     "Their application_partition_id will be reassigned to the target CP partition, which " +
                     "may invalidate the deletion_partition_generation fence used by " +
                     "DeletionStateTransitionTask.fallbackHardDeleteTimeout(). " +
                     "Migration proceeds. Monitor for delayed hard-delete finalizer confirmation. " +
                     "Applications: [{}]",
                     op.getId(), batchNumber, inflightDeletes.size(), appNames);
        }

        // --- Step 2: Resolve target cluster partition for the whole batch ---
        UUID targetClusterPartitionId = partitionService.resolveClusterPartitionForCp(
                targetCpId, partitionProperties.getClusterTargetSize());

        // --- Steps 3+4: Per-cluster — capture source IDs + resolve target app partition ---
        // Ordered map preserves cluster order for deterministic logging.
        Map<UUID, UUID> clusterToSrcClusterPartition = new LinkedHashMap<>();
        Map<UUID, UUID> clusterToSrcAppPartition     = new LinkedHashMap<>();
        Map<UUID, UUID> clusterToTgtAppPartition     = new LinkedHashMap<>();

        for (UUID clusterId : clusterIds) {
            // Step 3a: capture source cluster partition (before migration)
            clusterToSrcClusterPartition.put(clusterId,
                    failoverRepository.getCurrentClusterPartitionId(clusterId));

            // Step 3b: capture source app partition (null if cluster has no active apps)
            Optional<UUID> srcAppPart =
                    failoverRepository.getCurrentApplicationPartitionIdForCluster(clusterId);
            clusterToSrcAppPartition.put(clusterId, srcAppPart.orElse(null));

            // Step 4: resolve target app partition (cluster-locality)
            if (srcAppPart.isPresent()) {
                // cluster-locality: reuse existing CP2 partition on retry, else greedy resolve
                UUID tgtAppPart = partitionService
                        .findApplicationPartitionForCluster(clusterId, targetCpId)
                        .orElseGet(() -> partitionService.resolveApplicationPartitionForCp(
                                targetCpId, partitionProperties.getApplicationTargetSize()));
                clusterToTgtAppPartition.put(clusterId, tgtAppPart);
            } else {
                // No active apps — no app partition needed
                clusterToTgtAppPartition.put(clusterId, null);
            }
        }

        // --- Step 5: Record source+target partition IDs in FOC (before any FK change) ---
        for (UUID clusterId : clusterIds) {
            failoverRepository.recordPartitionIds(
                    op.getId(), clusterId,
                    clusterToSrcClusterPartition.get(clusterId),
                    targetClusterPartitionId,
                    clusterToSrcAppPartition.get(clusterId),
                    clusterToTgtAppPartition.get(clusterId));
        }

        // --- Step 6: Migrate clusters (CP + cluster partition) in one batch UPDATE ---
        int migratedClusters = failoverRepository.migrateClustersCpAndPartition(
                clusterIds, targetCpId, targetClusterPartitionId);

        // --- Step 7: Per-cluster app partition migration ---
        // ALL apps are moved, including HARD_DELETE (warning already emitted in Step 1).
        int totalAppsReassigned = 0;
        for (UUID clusterId : clusterIds) {
            UUID tgtAppPart = clusterToTgtAppPartition.get(clusterId);
            if (tgtAppPart != null) {
                totalAppsReassigned += failoverRepository.migrateApplicationPartitionForCluster(
                        clusterId, tgtAppPart);
            }
        }

        // --- Step 8: Bump generations on all affected partition IDs ---
        Set<UUID> clusterPartitionsToInvalidate = new LinkedHashSet<>();
        clusterToSrcClusterPartition.values().stream()
                .filter(Objects::nonNull).forEach(clusterPartitionsToInvalidate::add);
        clusterPartitionsToInvalidate.add(targetClusterPartitionId);
        partitionService.bumpClusterPartitionGenerations(clusterPartitionsToInvalidate);

        Set<UUID> appPartitionsToInvalidate = new LinkedHashSet<>();
        clusterToSrcAppPartition.values().stream()
                .filter(Objects::nonNull).forEach(appPartitionsToInvalidate::add);
        clusterToTgtAppPartition.values().stream()
                .filter(Objects::nonNull).forEach(appPartitionsToInvalidate::add);
        partitionService.bumpApplicationPartitionGenerations(appPartitionsToInvalidate);

        // --- Step 9: Reset app statuses to UNKNOWN (same CURRENT_TIMESTAMP as migrated_at) ---
        // Only resets active apps (deletion_mode IS NULL) — HARD_DELETE apps keep their status.
        int resetApps = applicationRepository.resetSyncAndHealthStatusForClusters(clusterIds);

        // --- Step 10: Stamp migrated_at + mark MIGRATED on FOC rows ---
        failoverRepository.markBatchMigrated(op.getId(), batchNumber);

        log.info("Failover operation {}: batch {} migrated — clusters={}, apps_reassigned={}, apps_reset={}, " +
                 "cluster_partitions_invalidated={}, app_partitions_invalidated={}",
                op.getId(), batchNumber, migratedClusters, totalAppsReassigned, resetApps,
                clusterPartitionsToInvalidate.size(), appPartitionsToInvalidate.size());

        // --- Step 11: Targeted cache invalidation (replaces null/null full-clear broadcast) ---
        // One event per unique affected partition — minimises cache eviction blast radius.
        for (UUID partitionId : clusterPartitionsToInvalidate) {
            eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.CLUSTER));
        }
        for (UUID partitionId : appPartitionsToInvalidate) {
            eventPublisher.publishEvent(
                    new PartitionChangedEvent(this, partitionId, PartitionType.APPLICATION));
        }
    }
}
