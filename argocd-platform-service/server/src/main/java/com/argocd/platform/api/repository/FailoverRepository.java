package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.request.FailoverFilter;
import com.argocd.platform.api.model.request.FailoverRequest;
import com.argocd.platform.api.model.response.ClusterBatchItem;
import com.argocd.platform.api.util.FailoverClusterStatus;
import com.argocd.platform.api.util.FailoverOperationStatus;
import com.argocd.platform.api.util.SuccessCondition;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import com.argocd.platform.db.jooq.tables.pojos.FailoverOperationClustersEntity;
import com.argocd.platform.db.jooq.tables.pojos.FailoverOperationsEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTER_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;
import static com.argocd.platform.db.jooq.Tables.FAILOVER_OPERATION_CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.FAILOVER_OPERATIONS;

/**
 * Data-access layer for {@code failover_operations} and {@code failover_operation_clusters}.
 *
 * <h3>Concurrency safety</h3>
 * <p>{@link #findInflightClusterIds} performs a service-level conflict check before insert.
 * A partial unique index on {@code failover_operation_clusters(cluster_id)} WHERE
 * {@code status IN ('PENDING','MIGRATED')} (see Liquibase v1.0.10) provides a
 * DB-level guarantee against the rare race where two concurrent POST requests pass
 * the service check simultaneously.
 */
@Repository
@RequiredArgsConstructor
public class FailoverRepository {

    private final DSLContext dsl;

    // -------------------------------------------------------------------------
    // Cluster filter resolution
    // -------------------------------------------------------------------------

    /**
     * Returns all clusters that match the request filter AND are NOT already assigned
     * to {@code targetCpId}.
     *
     * <h3>Filter semantics</h3>
     * <ul>
     *   <li>AND between all specified filter fields.</li>
     *   <li>OR between {@code labelSelectors} entries.</li>
     *   <li>AND within each {@code labelSelectors} entry.</li>
     *   <li>Label values are POSIX regex (Postgres {@code ~}, case-sensitive, unanchored).
     *       A missing label key evaluates to NULL → excluded (correct AND semantics).</li>
     * </ul>
     *
     * <p>Results are sorted by cluster name for stable batch assignment across calls.
     *
     * @param request     the failover request containing filter parameters
     * @param targetCpId  ID of the target CP; clusters already on this CP are excluded
     * @return matching clusters, sorted by name; empty list if none match
     */
    public List<ClustersEntity> resolveClusters(FailoverRequest request, UUID targetCpId) {
        // Base condition: exclude clusters already on the target CP
        Condition cond = CLUSTERS.CONTROL_PLANE_ID.ne(targetCpId);

        FailoverFilter filter = request.getFilter();

        if (filter != null && filter.getClusterIds() != null && !filter.getClusterIds().isEmpty()) {
            cond = cond.and(CLUSTERS.ID.in(filter.getClusterIds()));
        }

        if (filter != null && filter.getClusterNames() != null && !filter.getClusterNames().isEmpty()) {
            cond = cond.and(CLUSTERS.NAME.in(filter.getClusterNames()));
        }

        if (filter != null && filter.getSourceControlPlanes() != null && !filter.getSourceControlPlanes().isEmpty()) {
            cond = cond.and(
                    CLUSTERS.CONTROL_PLANE_ID.in(
                            DSL.select(CONTROL_PLANES.ID)
                                    .from(CONTROL_PLANES)
                                    .where(CONTROL_PLANES.NAME.in(filter.getSourceControlPlanes()))));
        }

        if (filter != null && filter.getLabelSelectors() != null && !filter.getLabelSelectors().isEmpty()) {
            Condition selectorOr = DSL.falseCondition();
            for (Map<String, String> selector : filter.getLabelSelectors()) {
                Condition selectorAnd = DSL.trueCondition();
                for (Map.Entry<String, String> entry : selector.entrySet()) {
                    // Extract JSONB key: clusters.labels ->> ?
                    // DSL.val() binds the key as a prepared-statement parameter (safe from injection).
                    // likeRegex() maps to Postgres ~ (POSIX, case-sensitive, unanchored).
                    // A missing key returns NULL → condition is NULL → excluded (correct for AND).
                    Field<String> labelVal = DSL.field(
                            "({0} ->> {1})",
                            String.class,
                            CLUSTERS.LABELS,
                            DSL.val(entry.getKey()));
                    selectorAnd = selectorAnd.and(labelVal.likeRegex(entry.getValue()));
                }
                selectorOr = selectorOr.or(selectorAnd);
            }
            cond = cond.and(selectorOr);
        }

        return dsl.selectFrom(CLUSTERS)
                .where(cond)
                .orderBy(CLUSTERS.NAME)
                .fetchInto(ClustersEntity.class);
    }

    /**
     * Returns the count of non-tombstoned applications belonging to any of the given clusters.
     *
     * <p>No additional status or deletion-mode filter is applied — in-flight HARD_DELETE apps
     * are included in the count.
     *
     * <p>Used by the dry-run path to report how many applications would be affected by the
     * failover without making any changes.
     *
     * @param clusterIds the resolved cluster IDs
     * @return total application count; {@code 0} if the list is empty or no apps exist
     */
    public int countApplicationsForClusters(List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return 0;
        }
        Integer count = dsl.select(DSL.count())
                .from(APPLICATIONS)
                .where(APPLICATIONS.CLUSTER_ID.in(clusterIds))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .fetchOneInto(Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Returns the source control-plane name for each cluster in the given list,
     * keyed by cluster ID.
     *
     * <p>Used by the service to build {@link ClusterBatchItem} entries with a
     * human-readable source CP name after filter resolution.
     *
     * @param clusterIds list of cluster IDs to look up
     * @return map of cluster ID → source control-plane name (entries absent if CP unassigned)
     */
    public Map<UUID, String> findSourceCpNamesByClusterIds(List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectDistinct(CLUSTERS.ID, CONTROL_PLANES.NAME)
                .from(CLUSTERS)
                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .where(CLUSTERS.ID.in(clusterIds))
                .fetch(r -> Map.entry(r.get(CLUSTERS.ID), r.get(CONTROL_PLANES.NAME)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // -------------------------------------------------------------------------
    // Conflict detection
    // -------------------------------------------------------------------------

    /**
     * Returns the subset of {@code clusterIds} that are currently part of an active
     * (non-dry-run) failover operation — i.e. their cluster row has status
     * {@code PENDING} or {@code MIGRATED} and the parent operation has status
     * {@code PENDING} or {@code AWAITING_BATCH_CONFIRMATION}.
     *
     * <p>An empty result means no conflicts and the caller may proceed with creation.
     * A non-empty result should cause the caller to throw a 409.
     *
     * <p>Note: this check is advisory-level. A partial unique index on
     * {@code failover_operation_clusters(cluster_id) WHERE status IN ('PENDING','MIGRATED')}
     * (v1.0.10) provides the authoritative DB-level guarantee.
     *
     * @param clusterIds the resolved cluster IDs from the filter
     * @return cluster IDs that are in-flight; empty if no conflicts
     */
    public List<UUID> findInflightClusterIds(List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return List.of();
        }
        return dsl.selectDistinct(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .from(FAILOVER_OPERATION_CLUSTERS)
                .join(FAILOVER_OPERATIONS)
                        .on(FAILOVER_OPERATIONS.ID.eq(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID))
                .where(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID.in(clusterIds))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.in(
                        FailoverClusterStatus.PENDING.name(),
                        FailoverClusterStatus.MIGRATED.name()))
                .and(FAILOVER_OPERATIONS.STATUS.in(
                        FailoverOperationStatus.PENDING.name(),
                        FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name()))
                .and(FAILOVER_OPERATIONS.DRY_RUN.eq(false))
                .fetchInto(UUID.class);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Inserts a new {@code failover_operations} row and returns the persisted entity
     * (with DB-generated id and timestamps).
     *
     * <p>Columns with DB defaults ({@code completed_clusters = 0}, {@code current_batch = 0},
     * {@code created_at / updated_at = now()}) are omitted — the DB supplies them.
     *
     * @param targetCpId          ID of the target control plane
     * @param totalClusters       total number of clusters resolved by the filter
     * @param totalBatches        number of batches ({@code ceil(total / batchSize)})
     * @param batchSize           clusters per batch; {@code null} = single batch
     * @param successCondition    confirmation requirement
     * @param dryRun              whether this is a dry-run (no cluster changes)
     * @param batchTimeoutSeconds seconds to wait per batch
     * @param status              initial status (PENDING for real, COMPLETED for dry-run)
     * @return the persisted entity with all DB-generated fields populated
     */
    public FailoverOperationsEntity createOperation(
            UUID targetCpId,
            int totalClusters,
            int totalBatches,
            Integer batchSize,
            SuccessCondition successCondition,
            boolean dryRun,
            int batchTimeoutSeconds,
            String status) {

        var insert = dsl.insertInto(FAILOVER_OPERATIONS)
                .set(FAILOVER_OPERATIONS.TARGET_CONTROL_PLANE_ID, targetCpId)
                .set(FAILOVER_OPERATIONS.TOTAL_CLUSTERS, totalClusters)
                .set(FAILOVER_OPERATIONS.TOTAL_BATCHES, totalBatches)
                .set(FAILOVER_OPERATIONS.SUCCESS_CONDITION, successCondition.name())
                .set(FAILOVER_OPERATIONS.BATCH_TIMEOUT_SECONDS, batchTimeoutSeconds)
                .set(FAILOVER_OPERATIONS.STATUS, status)
                .set(FAILOVER_OPERATIONS.DRY_RUN, dryRun);

        // Only set batch_size when non-null; the column is nullable and has no DB default
        if (batchSize != null) {
            insert = insert.set(FAILOVER_OPERATIONS.BATCH_SIZE, batchSize);
        }

        return insert.returning().fetchOneInto(FailoverOperationsEntity.class);
    }

    /**
     * Batch-inserts per-cluster rows into {@code failover_operation_clusters}.
     *
     * <p>Columns with DB defaults ({@code status = PENDING}, {@code migrated_at = null},
     * {@code confirmed_at = null}) are omitted — the DB supplies them.
     *
     * @param clusterRows pre-built entities with operationId, clusterId, batchNumber,
     *                    and sourceControlPlaneId populated; status/timestamps are DB defaults
     */
    public void createOperationClusters(List<FailoverOperationClustersEntity> clusterRows) {
        if (clusterRows == null || clusterRows.isEmpty()) {
            return;
        }
        dsl.batch(
                clusterRows.stream()
                        .map(row -> dsl.insertInto(FAILOVER_OPERATION_CLUSTERS)
                                .set(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID, row.getOperationId())
                                .set(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID, row.getClusterId())
                                .set(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER, row.getBatchNumber())
                                .set(FAILOVER_OPERATION_CLUSTERS.SOURCE_CONTROL_PLANE_ID,
                                        row.getSourceControlPlaneId()))
                        .collect(Collectors.toList())
        ).execute();
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Looks up a failover operation by id.
     *
     * @param id the operation UUID
     * @return the operation entity, or empty if not found
     */
    public Optional<FailoverOperationsEntity> findOperationById(UUID id) {
        return dsl.selectFrom(FAILOVER_OPERATIONS)
                .where(FAILOVER_OPERATIONS.ID.eq(id))
                .fetchOptionalInto(FailoverOperationsEntity.class);
    }

    /**
     * Returns per-cluster batch items for the given operation, enriched with
     * cluster name and source control-plane name via JOIN.
     *
     * <p>Results are ordered by batch number then cluster name for stable display.
     *
     * @param operationId the parent operation UUID
     * @return enriched cluster items; empty if no rows exist (e.g. dry-run operation)
     */
    public List<ClusterBatchItem> findClusterBatchItems(UUID operationId) {
        // Alias the second NAME column to avoid collision with CLUSTERS.NAME
        Field<String> sourceCpName = CONTROL_PLANES.NAME.as("source_cp_name");

        return dsl.select(
                        FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID,
                        CLUSTERS.NAME,
                        sourceCpName,
                        FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER,
                        FAILOVER_OPERATION_CLUSTERS.STATUS,
                        FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT,
                        FAILOVER_OPERATION_CLUSTERS.CONFIRMED_AT)
                .from(FAILOVER_OPERATION_CLUSTERS)
                .join(CLUSTERS).on(CLUSTERS.ID.eq(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID))
                .join(CONTROL_PLANES)
                        .on(CONTROL_PLANES.ID.eq(FAILOVER_OPERATION_CLUSTERS.SOURCE_CONTROL_PLANE_ID))
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .orderBy(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER, CLUSTERS.NAME)
                .fetch(r -> ClusterBatchItem.builder()
                        .clusterId(r.get(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID))
                        .clusterName(r.get(CLUSTERS.NAME))
                        .sourceControlPlane(r.get(sourceCpName))
                        .batchNumber(r.get(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER))
                        .status(r.get(FAILOVER_OPERATION_CLUSTERS.STATUS))
                        .migratedAt(r.get(FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT))
                        .confirmedAt(r.get(FAILOVER_OPERATION_CLUSTERS.CONFIRMED_AT))
                        .build());
    }

    // =========================================================================
    // Batch scheduler — discovery queries (no row lock)
    // =========================================================================

    /**
     * Returns IDs of all non-dry-run failover operations in {@code PENDING} status.
     *
     * <p>Called by the batch scheduler to find operations whose first batch has not
     * yet been migrated. No lock is held — the scheduler acquires a row-level lock
     * per operation via {@link #lockOperationById} inside a per-operation transaction.
     *
     * @return list of operation UUIDs in PENDING status; empty if none
     */
    public List<UUID> findPendingOperationIds() {
        return dsl.select(FAILOVER_OPERATIONS.ID)
                .from(FAILOVER_OPERATIONS)
                .where(FAILOVER_OPERATIONS.STATUS.eq(FailoverOperationStatus.PENDING.name()))
                .and(FAILOVER_OPERATIONS.DRY_RUN.eq(false))
                .fetchInto(UUID.class);
    }

    /**
     * Returns IDs of all non-dry-run failover operations in
     * {@code AWAITING_BATCH_CONFIRMATION} status.
     *
     * <p>Called by the batch scheduler for confirmation polling.
     *
     * @return list of operation UUIDs awaiting confirmation; empty if none
     */
    public List<UUID> findAwaitingConfirmationOperationIds() {
        return dsl.select(FAILOVER_OPERATIONS.ID)
                .from(FAILOVER_OPERATIONS)
                .where(FAILOVER_OPERATIONS.STATUS.eq(
                        FailoverOperationStatus.AWAITING_BATCH_CONFIRMATION.name()))
                .and(FAILOVER_OPERATIONS.DRY_RUN.eq(false))
                .fetchInto(UUID.class);
    }

    // =========================================================================
    // Batch scheduler — row locking
    // =========================================================================

    /**
     * Attempts to acquire a row-level lock on a single failover operation using
     * {@code SELECT FOR UPDATE SKIP LOCKED}.
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>the operation does not exist</li>
     *   <li>the operation's status has changed (already processed by this or another instance)</li>
     *   <li>another scheduler instance holds the lock ({@code SKIP LOCKED} skips it silently)</li>
     * </ul>
     *
     * <p><strong>Must be called inside an active transaction</strong> so the lock is held for the
     * duration of the calling method's work. The lock is released on transaction commit or rollback.
     *
     * @param id             the operation UUID to lock
     * @param requiredStatus the expected status; any status mismatch returns empty
     * @return the locked operation entity, or empty if the lock could not be acquired
     */
    public Optional<FailoverOperationsEntity> lockOperationById(UUID id, String requiredStatus) {
        return dsl.selectFrom(FAILOVER_OPERATIONS)
                .where(FAILOVER_OPERATIONS.ID.eq(id))
                .and(FAILOVER_OPERATIONS.STATUS.eq(requiredStatus))
                .and(FAILOVER_OPERATIONS.DRY_RUN.eq(false))
                .forUpdate()
                .skipLocked()
                .fetchOptionalInto(FailoverOperationsEntity.class);
    }

    // =========================================================================
    // Batch scheduler — batch migration
    // =========================================================================

    /**
     * Returns all cluster IDs enrolled in an operation, across all batches.
     * Results are ordered by cluster ID for deterministic processing.
     *
     * <p>Used by {@code FailoverBatchService.processRollback()} to find PENDING cluster rows
     * (future batches) whose FKs were never changed and only need a status update to
     * {@code ROLLED_BACK}.
     *
     * @param operationId the parent operation UUID
     * @return all enrolled cluster IDs; empty if the operation has no cluster rows
     */
    public List<UUID> getClusterIdsForOperation(UUID operationId) {
        return dsl.select(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .from(FAILOVER_OPERATION_CLUSTERS)
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .orderBy(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .fetchInto(UUID.class);
    }

    /**
     * Returns the cluster IDs assigned to a specific batch within an operation.
     * Results are ordered by cluster ID for stable, deterministic processing order.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the 1-indexed batch number
     * @return cluster IDs in the batch; empty if no rows exist
     */
    public List<UUID> getClusterIdsForBatch(UUID operationId, int batchNumber) {
        return dsl.select(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .from(FAILOVER_OPERATION_CLUSTERS)
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .orderBy(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .fetchInto(UUID.class);
    }

    /**
     * Updates {@code clusters.control_plane_id} for all given cluster IDs.
     *
     * <p>Option A: cluster partitions are globally scoped — {@code cluster_partition_id}
     * does NOT change during failover. Only the control-plane assignment is updated.
     * The partition stays; the cluster's desired-state context (CP) moves.
     *
     * @param clusterIds list of cluster IDs to migrate
     * @param targetCpId the target control plane ID
     * @return number of rows updated
     */
    public int migrateClustersCp(List<UUID> clusterIds, UUID targetCpId) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return 0;
        }
        return dsl.update(CLUSTERS)
                .set(CLUSTERS.CONTROL_PLANE_ID, targetCpId)
                .where(CLUSTERS.ID.in(clusterIds))
                .execute();
    }

    /**
     * Sets {@code status = MIGRATED} and {@code migrated_at = CURRENT_TIMESTAMP} on all
     * {@code PENDING} cluster rows in the given batch.
     *
     * <p>{@code CURRENT_TIMESTAMP} is evaluated once per transaction in Postgres, so
     * {@code migrated_at} equals the {@code updated_at} written by the preceding application
     * status reset (both use DB-side {@code CURRENT_TIMESTAMP} in the same transaction).
     * This ensures the timestamp gate {@code applications.updated_at > migrated_at} is
     * {@code false} until a real ArgoCD event bumps the application's {@code updated_at}.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the batch being migrated
     * @return number of rows updated
     */
    public int markBatchMigrated(UUID operationId, int batchNumber) {
        return dsl.update(FAILOVER_OPERATION_CLUSTERS)
                .set(FAILOVER_OPERATION_CLUSTERS.STATUS, FailoverClusterStatus.MIGRATED.name())
                .set(FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT, DSL.currentLocalDateTime())
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.PENDING.name()))
                .execute();
    }

    // =========================================================================
    // Batch scheduler — confirmation polling
    // =========================================================================

    /**
     * Returns the maximum {@code migrated_at} timestamp across all {@code MIGRATED} cluster
     * rows in the given batch.
     *
     * <p>Kept for diagnostic/logging purposes. For the actual timeout check prefer
     * {@link #hasBatchTimedOut} which avoids JVM/DB timezone skew by keeping both sides
     * of the comparison inside the database.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the 1-indexed batch number
     * @return the latest {@code migrated_at} for MIGRATED rows, or empty if none exist
     */
    public Optional<LocalDateTime> getBatchMigratedAt(UUID operationId, int batchNumber) {
        LocalDateTime result = dsl
                .select(DSL.max(FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT))
                .from(FAILOVER_OPERATION_CLUSTERS)
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name()))
                .fetchOne()
                .value1();
        return Optional.ofNullable(result);
    }

    /**
     * Returns {@code true} if the current batch has exceeded its timeout window.
     *
     * <p>The comparison is done entirely in the database
     * ({@code LOCALTIMESTAMP > MAX(migrated_at) + N seconds}) to avoid the JVM/DB timezone
     * skew that occurs when comparing {@code LocalDateTime.now()} (JVM-local) against a
     * {@code LOCALTIMESTAMP}-written column (DB-local). When the two clocks differ by more
     * than {@code batchTimeoutSeconds}, the Java-side comparison yields immediate spurious
     * timeouts (JVM ahead of DB) or a permanently open window (JVM behind DB).
     *
     * <p>Returns {@code false} when there are no {@code MIGRATED} rows for the batch:
     * {@code MAX(migrated_at)} is {@code NULL} → the boolean expression is {@code NULL} →
     * treated as not timed out.
     *
     * @param operationId    the parent operation UUID
     * @param batchNumber    the 1-indexed batch number
     * @param timeoutSeconds timeout window in seconds
     * @return {@code true} if {@code LOCALTIMESTAMP > MAX(migrated_at) + timeoutSeconds};
     *         {@code false} otherwise (including the no-rows case)
     */
    public boolean hasBatchTimedOut(UUID operationId, int batchNumber, int timeoutSeconds) {
        // Both LOCALTIMESTAMP and MIGRATED_AT are stamped with DSL.currentLocalDateTime()
        // (→ SQL LOCALTIMESTAMP).  Keeping the comparison inside the DB means both sides
        // share the same server clock — no cross-clock skew, regardless of JVM timezone.
        Field<Boolean> timedOut = DSL.field(
                "LOCALTIMESTAMP > MAX({0}) + ({1} * INTERVAL '1 second')",
                Boolean.class,
                FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT,
                DSL.val(timeoutSeconds));

        return Boolean.TRUE.equals(
                dsl.select(timedOut)
                   .from(FAILOVER_OPERATION_CLUSTERS)
                   .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                   .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                   .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name()))
                   .fetchOneInto(Boolean.class));
    }

    /**
     * Returns the count of {@code MIGRATED} cluster rows in the given batch.
     *
     * <p>Used alongside {@link #countUnconfirmedMigratedClusters} to enforce the
     * confirmation guard: {@code migratedCount > 0 AND unconfirmedCount == 0}.
     * The {@code > 0} guard prevents a vacuously-confirmed empty batch from being
     * advanced if no clusters were ever migrated.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the 1-indexed batch number
     * @return count of MIGRATED rows; 0 if the batch has not been migrated yet
     */
    public int countMigratedClusters(UUID operationId, int batchNumber) {
        return dsl.fetchCount(
                dsl.selectOne()
                   .from(FAILOVER_OPERATION_CLUSTERS)
                   .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                   .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                   .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name())));
    }

    /**
     * Returns the count of {@code MIGRATED} clusters in the batch that are not yet confirmed.
     *
     * <p>A cluster is <em>unconfirmed</em> when it has at least one active application where:
     * <ul>
     *   <li>{@code updated_at <= migrated_at} — the application has not been updated since
     *       migration (the status reset sets {@code updated_at = CURRENT_TIMESTAMP} in the same
     *       transaction as {@code migrated_at}, so both are equal — the gate correctly blocks
     *       until a real ArgoCD event arrives and bumps {@code updated_at}), OR</li>
     *   <li>the relevant status field does not satisfy the {@code successCondition}.</li>
     * </ul>
     *
     * <p>A cluster with no active applications is trivially confirmed ({@code EXISTS} returns
     * {@code false} → the cluster does not appear in the unconfirmed count).
     *
     * @param operationId      the parent operation UUID
     * @param batchNumber      the 1-indexed batch number
     * @param successCondition the condition each application must satisfy
     * @return count of unconfirmed MIGRATED clusters; {@code 0} means all are confirmed
     */
    public int countUnconfirmedMigratedClusters(
            UUID operationId, int batchNumber, SuccessCondition successCondition) {

        Condition notSatisfied = buildNotSatisfiedCondition(successCondition);

        Integer result = dsl.select(DSL.count())
                .from(FAILOVER_OPERATION_CLUSTERS)
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name()))
                .and(DSL.exists(
                        DSL.select(DSL.inline(1))
                           .from(APPLICATIONS)
                           .where(APPLICATIONS.CLUSTER_ID
                                   .eq(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID))
                           .and(APPLICATIONS.DELETED_AT.isNull())
                           .and(APPLICATIONS.DELETION_MODE.isNull())
                           .and(APPLICATIONS.UPDATED_AT
                                   .lessOrEqual(FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT)
                                   .or(notSatisfied))))
                .fetchOneInto(Integer.class);

        return result != null ? result : 0;
    }

    /**
     * Builds a jOOQ {@link Condition} expressing "this application does NOT satisfy
     * the given success condition". Used as the non-satisfying branch inside the
     * confirmation EXISTS subquery.
     *
     * @param successCondition the required condition
     * @return a condition that is true when an application fails the requirement
     */
    private Condition buildNotSatisfiedCondition(SuccessCondition successCondition) {
        return switch (successCondition) {
            case SYNCED  -> APPLICATIONS.SYNC_STATUS.ne("SYNCED");
            case HEALTHY -> APPLICATIONS.HEALTH_STATUS.ne("HEALTHY");
            case CREATED -> APPLICATIONS.STATUS.ne("ACTIVE");
        };
    }

    /**
     * Sets {@code status = CONFIRMED} and {@code confirmed_at = CURRENT_TIMESTAMP} on all
     * {@code MIGRATED} cluster rows in the given batch.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the batch that passed confirmation
     * @return number of cluster rows confirmed; callers use this to increment
     *         {@code failover_operations.completed_clusters} by the actual confirmed count
     *         (important for the last batch, which may be smaller than {@code batch_size})
     */
    public int confirmBatch(UUID operationId, int batchNumber) {
        return dsl.update(FAILOVER_OPERATION_CLUSTERS)
                .set(FAILOVER_OPERATION_CLUSTERS.STATUS, FailoverClusterStatus.CONFIRMED.name())
                .set(FAILOVER_OPERATION_CLUSTERS.CONFIRMED_AT, DSL.currentLocalDateTime())
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name()))
                .execute();
    }

    /**
     * Sets {@code status = FAILED} on all {@code MIGRATED} cluster rows in the given batch.
     *
     * <p>Called when the batch timeout elapses before all clusters are confirmed.
     * The operation transitions to {@code TIMED_OUT} after this call. Cluster migrations
     * already applied to {@code clusters.control_plane_id} are <strong>not</strong> rolled
     * back — that is left to the {@code /rollback} recovery API (Part 6).
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the timed-out batch
     * @return number of rows marked FAILED
     */
    public int failBatch(UUID operationId, int batchNumber) {
        return dsl.update(FAILOVER_OPERATION_CLUSTERS)
                .set(FAILOVER_OPERATION_CLUSTERS.STATUS, FailoverClusterStatus.FAILED.name())
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.MIGRATED.name()))
                .execute();
    }

    // =========================================================================
    // Batch scheduler — operation progress updates
    // =========================================================================

    // =========================================================================
    // Batch scheduler — partition tracking (for generation bumps)
    // =========================================================================

    /**
     * Returns the current {@code cluster_partition_id} for a given cluster.
     * Used after migration to collect partition IDs whose generation should be bumped
     * (so ArgoCD plugin polls see the updated CP fan-out).
     *
     * <p>In Option A the partition does NOT change during failover — this is a read-only
     * lookup for the generation-bump step, not a pre-migration capture.
     *
     * @param clusterId the cluster UUID
     * @return the current cluster partition UUID (NOT NULL by schema constraint)
     */
    public UUID getCurrentClusterPartitionId(UUID clusterId) {
        return dsl.select(CLUSTERS.CLUSTER_PARTITION_ID)
                .from(CLUSTERS)
                .where(CLUSTERS.ID.eq(clusterId))
                .fetchOne(CLUSTERS.CLUSTER_PARTITION_ID);
    }

    /**
     * Returns the {@code application_partition_id} of an arbitrary active application
     * belonging to {@code clusterId}, or empty if the cluster has no active applications.
     *
     * <p>Used to capture the source application partition UUID before migration.
     * If multiple apps belong to different partitions (edge case during transition),
     * any one of them is representative; the migration step moves all of them.
     *
     * @param clusterId the cluster UUID
     * @return source application partition UUID, or empty if cluster has no active apps
     */
    public Optional<UUID> getCurrentApplicationPartitionIdForCluster(UUID clusterId) {
        return dsl.select(APPLICATIONS.APPLICATION_PARTITION_ID)
                .from(APPLICATIONS)
                .where(APPLICATIONS.CLUSTER_ID.eq(clusterId))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .limit(1)
                .fetchOptional(APPLICATIONS.APPLICATION_PARTITION_ID);
    }

    // =========================================================================
    // Batch scheduler — deletion guard (warn-only)
    // =========================================================================

    /**
     * Projection used by the deletion guard warning in {@code FailoverBatchService}.
     *
     * @param clusterId              the cluster being migrated
     * @param appId                  the application UUID in HARD_DELETE state
     * @param appName                human-readable name (for warning log)
     * @param currentAppPartitionId  the application's current partition (for generation bump)
     */
    public record InflightHardDeleteApp(
            UUID clusterId,
            UUID appId,
            String appName,
            UUID currentAppPartitionId) {}

    /**
     * Returns all non-tombstoned applications in {@code HARD_DELETE} state whose cluster
     * is in the given list. Used by {@code FailoverBatchService.migrateBatch()} to emit
     * a warning before reassigning {@code application_partition_id}.
     *
     * <p>These applications are NOT excluded from migration — only a warning is logged.
     * See the code comment in {@code DeletionStateTransitionTask.fallbackHardDeleteTimeout()}
     * for a full description of the race window.
     *
     * @param clusterIds the clusters being migrated in the current batch
     * @return list of in-flight HARD_DELETE applications; empty if none
     */
    public List<InflightHardDeleteApp> findInflightHardDeleteAppsForClusters(List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(
                        APPLICATIONS.CLUSTER_ID,
                        APPLICATIONS.ID,
                        APPLICATIONS.NAME,
                        APPLICATIONS.APPLICATION_PARTITION_ID)
                .from(APPLICATIONS)
                .where(APPLICATIONS.CLUSTER_ID.in(clusterIds))
                .and(APPLICATIONS.DELETION_MODE.eq("HARD_DELETE"))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .fetch(r -> new InflightHardDeleteApp(
                        r.get(APPLICATIONS.CLUSTER_ID),
                        r.get(APPLICATIONS.ID),
                        r.get(APPLICATIONS.NAME),
                        r.get(APPLICATIONS.APPLICATION_PARTITION_ID)));
    }

    /**
     * Updates {@code status}, {@code current_batch}, {@code completed_clusters}, and
     * {@code updated_at} on the operation row in a single statement.
     *
     * <p>All four fields are written together so the operation row always reflects a
     * consistent state — no partial-update windows for readers of the GET endpoint.
     *
     * @param id                the operation UUID
     * @param status            the new operation status
     * @param currentBatch      the batch number currently being processed
     * @param completedClusters cumulative count of CONFIRMED clusters across all batches
     */
    public void updateOperationProgress(UUID id, String status, int currentBatch, int completedClusters) {
        dsl.update(FAILOVER_OPERATIONS)
                .set(FAILOVER_OPERATIONS.STATUS, status)
                .set(FAILOVER_OPERATIONS.CURRENT_BATCH, currentBatch)
                .set(FAILOVER_OPERATIONS.COMPLETED_CLUSTERS, completedClusters)
                .set(FAILOVER_OPERATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(FAILOVER_OPERATIONS.ID.eq(id))
                .execute();
    }

    // =========================================================================
    // Part 6 — Recovery APIs (cancel / retry / rollback)
    // =========================================================================

    /**
     * Acquires a row-level lock on a failover operation for recovery endpoints
     * ({@code /cancel}, {@code /retry}, {@code /rollback}).
     *
     * <p>Unlike {@link #lockOperationById} (which uses {@code SKIP LOCKED} for the scheduler),
     * this method uses plain {@code FOR UPDATE} — it <em>blocks</em> until the scheduler
     * releases its lock. This guarantees that a recovery call issued concurrently with an
     * in-progress batch migration waits for the migration to commit before it proceeds, rather
     * than silently returning empty.
     *
     * <p>Returns {@link Optional#empty()} only when:
     * <ul>
     *   <li>the operation does not exist</li>
     *   <li>the operation's status is not in {@code requiredStatuses}</li>
     *   <li>the operation is a dry-run (dry-runs are never persisted as real rows)</li>
     * </ul>
     *
     * @param id               the operation UUID to lock
     * @param requiredStatuses allowable statuses; any mismatch returns empty
     * @return the locked entity, or empty if not lockable
     */
    public Optional<FailoverOperationsEntity> lockOperationForRecovery(UUID id, List<String> requiredStatuses) {
        return dsl.selectFrom(FAILOVER_OPERATIONS)
                .where(FAILOVER_OPERATIONS.ID.eq(id))
                .and(FAILOVER_OPERATIONS.STATUS.in(requiredStatuses))
                .and(FAILOVER_OPERATIONS.DRY_RUN.eq(false))
                .forUpdate()
                // No skipLocked() — recovery must wait for the scheduler to release the lock.
                // A concurrent migrateBatch() will hold FOR UPDATE SKIP LOCKED; this call blocks
                // until that transaction commits, then reads the post-commit state.
                .fetchOptionalInto(FailoverOperationsEntity.class);
    }

    /**
     * Updates only {@code status} and {@code updated_at} on an operation row.
     *
     * <p>Used by recovery endpoints where batch counters ({@code current_batch},
     * {@code completed_clusters}) do not change — e.g. cancel, retry, rollback.
     *
     * @param id        the operation UUID
     * @param newStatus the target status
     */
    public void updateOperationStatus(UUID id, String newStatus) {
        dsl.update(FAILOVER_OPERATIONS)
                .set(FAILOVER_OPERATIONS.STATUS, newStatus)
                .set(FAILOVER_OPERATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(FAILOVER_OPERATIONS.ID.eq(id))
                .execute();
    }

    // ---- Retry ---------------------------------------------------------------

    /**
     * Atomically transitions all {@code FAILED} cluster rows in {@code batchNumber} to
     * {@code MIGRATED} and stamps {@code migrated_at = CURRENT_TIMESTAMP}.
     *
     * <p>Re-stamping {@code migrated_at} is critical for retry correctness: without it
     * the timeout anchor ({@code MAX(migrated_at) + batchTimeoutSeconds}) is still the
     * original migration timestamp, so the confirmation poller would immediately re-timeout
     * on the next tick rather than opening a fresh confirmation window.
     *
     * <p>Uses {@code UPDATE...RETURNING} to return the cluster IDs that were actually
     * restamped, so the caller can reset application statuses and bump partition generations
     * only for the affected clusters.
     *
     * @param operationId the parent operation UUID
     * @param batchNumber the timed-out batch to retry
     * @return cluster IDs whose rows were restamped; empty if no FAILED rows existed
     */
    public List<UUID> restampFailedClustersAsMigrated(UUID operationId, int batchNumber) {
        return dsl.update(FAILOVER_OPERATION_CLUSTERS)
                .set(FAILOVER_OPERATION_CLUSTERS.STATUS, FailoverClusterStatus.MIGRATED.name())
                .set(FAILOVER_OPERATION_CLUSTERS.MIGRATED_AT, DSL.currentLocalDateTime())
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.BATCH_NUMBER.eq(batchNumber))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.eq(FailoverClusterStatus.FAILED.name()))
                .returning(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .fetch(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID);
    }

    // ---- Rollback ------------------------------------------------------------

    /**
     * Minimal projection for rollback: the cluster UUID and its pre-migration source CP.
     *
     * <p>In Option A, partition IDs never change during failover — only
     * {@code clusters.control_plane_id} is reverted. No partition FK tracking needed.
     *
     * @param clusterId            the cluster UUID
     * @param sourceControlPlaneId CP1 — the CP to restore the cluster to
     */
    public record RollbackTarget(
            UUID clusterId,
            UUID sourceControlPlaneId) {}

    /**
     * Returns all cluster rows that were <em>actually migrated</em> (status = MIGRATED, CONFIRMED,
     * or FAILED) for the given operation.
     *
     * <p>PENDING rows are excluded — those clusters never had their {@code control_plane_id} or
     * partition FKs changed, so no reversal is needed. The rollback code marks PENDING rows as
     * ROLLED_BACK separately (no FK update required).
     *
     * @param operationId the parent operation UUID
     * @return rollback targets; empty if no clusters were ever migrated (e.g. cancel before batch 1)
     */
    public List<RollbackTarget> findRollbackTargets(UUID operationId) {
        return dsl.select(
                        FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID,
                        FAILOVER_OPERATION_CLUSTERS.SOURCE_CONTROL_PLANE_ID)
                .from(FAILOVER_OPERATION_CLUSTERS)
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.STATUS.in(
                        FailoverClusterStatus.MIGRATED.name(),
                        FailoverClusterStatus.CONFIRMED.name(),
                        FailoverClusterStatus.FAILED.name()))
                .orderBy(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID)
                .fetch(r -> new RollbackTarget(
                        r.get(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID),
                        r.get(FAILOVER_OPERATION_CLUSTERS.SOURCE_CONTROL_PLANE_ID)));
    }

    /**
     * Restores a single cluster's {@code control_plane_id} to its pre-migration (CP1) value.
     *
     * <p>In Option A, {@code cluster_partition_id} does NOT change during failover or rollback —
     * partitions are globally scoped. Only the control-plane assignment is reverted.
     *
     * @param clusterId         the cluster to restore
     * @param srcControlPlaneId CP1 ID to restore to
     * @return number of rows updated (expected: 1)
     */
    public int restoreClusterCp(UUID clusterId, UUID srcControlPlaneId) {
        return dsl.update(CLUSTERS)
                .set(CLUSTERS.CONTROL_PLANE_ID, srcControlPlaneId)
                .where(CLUSTERS.ID.eq(clusterId))
                .execute();
    }

    /**
     * Sets {@code status = ROLLED_BACK} on the given cluster rows within an operation.
     *
     * <p>Called once per rollback, covering all clusters whose FKs were reversed.
     * PENDING rows that were never migrated are also marked ROLLED_BACK via a separate
     * call from {@code FailoverBatchService.processRollback()} to ensure every cluster
     * row reaches a terminal status.
     *
     * @param operationId the parent operation UUID
     * @param clusterIds  the cluster IDs to mark (may span multiple batches)
     */
    public void markClustersRolledBack(UUID operationId, List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return;
        }
        dsl.update(FAILOVER_OPERATION_CLUSTERS)
                .set(FAILOVER_OPERATION_CLUSTERS.STATUS, FailoverClusterStatus.ROLLED_BACK.name())
                .where(FAILOVER_OPERATION_CLUSTERS.OPERATION_ID.eq(operationId))
                .and(FAILOVER_OPERATION_CLUSTERS.CLUSTER_ID.in(clusterIds))
                .execute();
    }
}
