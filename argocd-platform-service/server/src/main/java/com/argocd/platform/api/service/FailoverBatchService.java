package com.argocd.platform.api.service;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <h3>Global partition migration (Option A)</h3>
 * <p>{@link #migrateBatch} updates only {@code clusters.control_plane_id} — partition FKs
 * ({@code cluster_partition_id}, {@code application_partition_id}) are globally scoped and
 * do NOT change during failover. Targeted {@link PartitionChangedEvent}s (one per unique
 * affected partition) are still emitted so ArgoCD sees the updated CP fan-out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailoverBatchService {

    private final FailoverRepository failoverRepository;
    private final ApplicationRepository applicationRepository;
    private final PartitionService partitionService;
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
     *   <li>Migrates batch 1 clusters: updates {@code clusters.control_plane_id} only
     *       (Option A — partition FKs are globally scoped and do not change),
     *       resets application statuses, stamps {@code migrated_at}.</li>
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

        // Timeout check — done entirely in DB to avoid JVM/DB timezone skew.
        // LocalDateTime.now() (JVM-local) vs LOCALTIMESTAMP (DB-local) diverge when the JVM
        // and the DB server run in different timezone settings: if the JVM clock is ahead of
        // the DB server's local time by more than batchTimeoutSeconds, the Java-side check
        // "now > migratedAt + timeout" fires immediately on the very first poll.
        if (failoverRepository.hasBatchTimedOut(op.getId(), currentBatch, batchTimeout)) {
            log.warn("Failover operation {}: batch {} timed out after {} s",
                    op.getId(), currentBatch, batchTimeout);
            failoverRepository.failBatch(op.getId(), currentBatch);
            failoverRepository.updateOperationProgress(
                    op.getId(),
                    FailoverOperationStatus.TIMED_OUT.name(),
                    currentBatch,
                    completedClusters);
            return;
        }

        // hasBatchTimedOut returns false (not true) when MAX(migrated_at) is NULL, i.e. no
        // MIGRATED rows exist.  Guard explicitly so the confirmation logic below is not entered.
        int migratedCount = failoverRepository.countMigratedClusters(op.getId(), currentBatch);
        if (migratedCount == 0) {
            log.warn("Failover operation {}: batch {} has no MIGRATED rows — skipping poll",
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

            // Read current (CP2 = target) partition IDs directly from the live cluster and
            // application rows. After a successful migrateBatch(), clusters.cluster_partition_id
            // and applications.application_partition_id already point to the CP2 partitions —
            // no stored tracking columns are needed.
            Set<UUID> clusterPartitions = new LinkedHashSet<>();
            Set<UUID> appPartitions = new LinkedHashSet<>();
            for (UUID clusterId : retriedClusterIds) {
                clusterPartitions.add(failoverRepository.getCurrentClusterPartitionId(clusterId));
                failoverRepository.getCurrentApplicationPartitionIdForCluster(clusterId)
                        .ifPresent(appPartitions::add);
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
     * {@code CANCELLED} operation, restoring the source control-plane assignment.
     *
     * <h3>What is reversed (Option A)</h3>
     * <p>Only clusters in {@code MIGRATED}, {@code CONFIRMED}, or {@code FAILED} status
     * actually had their {@code control_plane_id} changed. For each:
     * <ul>
     *   <li>{@code clusters.control_plane_id} → {@code sourceControlPlaneId} (from FOC row)</li>
     *   <li>Partition FKs ({@code cluster_partition_id}, {@code application_partition_id}) are
     *       <strong>not changed</strong> — they are globally scoped and were never modified
     *       during migration. Only the generation is bumped to signal CP fan-out change.</li>
     * </ul>
     *
     * <p>{@code PENDING} clusters (batches not yet started) are marked {@code ROLLED_BACK}
     * but their FK is not touched — it was never changed.
     *
     * <h3>After rollback</h3>
     * <p>Application statuses are reset to UNKNOWN for reverted clusters so that the CP1
     * ArgoCD re-evaluates their state and emits fresh status events. Partition generations are
     * bumped for all affected CP1 and CP2 partitions, and targeted {@link PartitionChangedEvent}s
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

        // In Option A, only clusters.control_plane_id is restored — partition FKs are unchanged.
        Set<UUID> clusterPartitionsToInvalidate = new LinkedHashSet<>();
        Set<UUID> appPartitionsToInvalidate     = new LinkedHashSet<>();
        List<UUID> rolledBackClusterIds          = new ArrayList<>();
        List<UUID> appsResetClusterIds           = new ArrayList<>();

        for (FailoverRepository.RollbackTarget t : targets) {
            if (t.sourceControlPlaneId() == null) {
                log.warn("Failover operation {}: cluster {} has no source CP recorded — " +
                         "skipping rollback for this cluster", op.getId(), t.clusterId());
                rolledBackClusterIds.add(t.clusterId());
                continue;
            }

            // Collect partition IDs for generation bumps (partition does not change in Option A)
            clusterPartitionsToInvalidate.add(
                    failoverRepository.getCurrentClusterPartitionId(t.clusterId()));
            failoverRepository.getCurrentApplicationPartitionIdForCluster(t.clusterId())
                    .ifPresent(appPartitionsToInvalidate::add);

            // Restore cluster: only revert control_plane_id back to CP1 (no partition FK change)
            failoverRepository.restoreClusterCp(t.clusterId(), t.sourceControlPlaneId());

            appsResetClusterIds.add(t.clusterId());
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

        // Bump partition generations so ArgoCD sees the reverted CP fan-out
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
    // Private — batch migration (Option A, simplified flow)
    // =========================================================================

    /**
     * Migrates a single batch of clusters from their source CP to the operation's target CP,
     * emitting targeted cache invalidation events so ArgoCD sees the updated CP fan-out.
     *
     * <p>In Option A (global partitions), cluster and application partition FKs do NOT change
     * during failover — only {@code clusters.control_plane_id} is updated.
     *
     * <ol>
     *   <li><b>Deletion guard</b>: query for in-flight HARD_DELETE apps and emit a WARNING.
     *       Migration continues regardless — the {@code deletion_partition_generation} fence
     *       is reliable in Option A because {@code application_partition_id} never changes.</li>
     *   <li><b>Collect partition IDs</b>: read the current {@code cluster_partition_id} and
     *       {@code application_partition_id} for each batch cluster (needed for generation
     *       bumps that signal CP fan-out changes to ArgoCD).</li>
     *   <li><b>Migrate clusters</b>: atomic UPDATE sets {@code control_plane_id} for all
     *       batch clusters. Partition FKs are unchanged.</li>
     *   <li><b>Bump generations</b>: increment generation on the affected cluster and
     *       application partitions to signal desired-state changes to ArgoCD.</li>
     *   <li><b>Reset app statuses</b>: resets sync/health to UNKNOWN. Uses the same
     *       {@code CURRENT_TIMESTAMP} as {@code migrated_at}, so the timestamp gate is
     *       {@code false} until a real ArgoCD event arrives.</li>
     *   <li><b>Mark MIGRATED</b>: stamps {@code migrated_at} and {@code status = MIGRATED}
     *       on {@code failover_operation_clusters} rows.</li>
     *   <li><b>Targeted cache events</b>: one {@link PartitionChangedEvent} per unique affected
     *       partition, deferred via {@code @TransactionalEventListener(AFTER_COMMIT)}.</li>
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
        // In Option A, deletion_partition_generation fence is reliable: application_partition_id
        // never changes during failover, so the fence value remains valid across CP moves.
        List<FailoverRepository.InflightHardDeleteApp> inflightDeletes =
                failoverRepository.findInflightHardDeleteAppsForClusters(clusterIds);
        if (!inflightDeletes.isEmpty()) {
            String appNames = inflightDeletes.stream()
                    .map(a -> a.appName() + " [cluster=" + a.clusterId() + "]")
                    .collect(Collectors.joining(", "));
            log.warn("Failover operation {}: batch {} — {} application(s) in HARD_DELETE state. " +
                     "Migration proceeds (partition_id unchanged in Option A). Applications: [{}]",
                     op.getId(), batchNumber, inflightDeletes.size(), appNames);
        }

        // --- Step 2: Collect partition IDs for generation bumps ---
        // Partitions do NOT change during failover (Option A). We read them now for the
        // generation-bump step so ArgoCD sees the updated CP fan-out after migration.
        Set<UUID> clusterPartitionsToInvalidate = new LinkedHashSet<>();
        Set<UUID> appPartitionsToInvalidate     = new LinkedHashSet<>();
        for (UUID clusterId : clusterIds) {
            clusterPartitionsToInvalidate.add(
                    failoverRepository.getCurrentClusterPartitionId(clusterId));
            failoverRepository.getCurrentApplicationPartitionIdForCluster(clusterId)
                    .ifPresent(appPartitionsToInvalidate::add);
        }

        // --- Step 3: Migrate clusters — update control_plane_id only ---
        // cluster_partition_id is globally scoped and stays unchanged.
        int migratedClusters = failoverRepository.migrateClustersCp(clusterIds, targetCpId);

        // --- Step 4: Bump generations on affected partitions ---
        // This signals a desired-state change to the ArgoCD ApplicationSet Plugin Generator:
        // the same partition now fans out to different CPs.
        partitionService.bumpClusterPartitionGenerations(clusterPartitionsToInvalidate);
        partitionService.bumpApplicationPartitionGenerations(appPartitionsToInvalidate);

        // --- Step 5: Reset app statuses to UNKNOWN (same CURRENT_TIMESTAMP as migrated_at) ---
        // Only resets active apps (deletion_mode IS NULL) — HARD_DELETE apps keep their status.
        int resetApps = applicationRepository.resetSyncAndHealthStatusForClusters(clusterIds);

        // --- Step 6: Stamp migrated_at + mark MIGRATED on FOC rows ---
        failoverRepository.markBatchMigrated(op.getId(), batchNumber);

        log.info("Failover operation {}: batch {} migrated — clusters={}, apps_reset={}, " +
                 "cluster_partitions_invalidated={}, app_partitions_invalidated={}",
                op.getId(), batchNumber, migratedClusters, resetApps,
                clusterPartitionsToInvalidate.size(), appPartitionsToInvalidate.size());

        // --- Step 7: Targeted cache invalidation ---
        // One event per unique affected partition — deferred via @TransactionalEventListener(AFTER_COMMIT).
        for (UUID partitionId : clusterPartitionsToInvalidate) {
            eventPublisher.publishEvent(new PartitionChangedEvent(this, partitionId, PartitionType.CLUSTER));
        }
        for (UUID partitionId : appPartitionsToInvalidate) {
            eventPublisher.publishEvent(
                    new PartitionChangedEvent(this, partitionId, PartitionType.APPLICATION));
        }
    }
}
