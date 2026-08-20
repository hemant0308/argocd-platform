package com.argocd.platform.api.repository;

import com.argocd.platform.api.util.DeletionMode;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;

@Repository
@RequiredArgsConstructor
public class ApplicationRepository {

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    /**
     * Carries the minimum fields needed by the deletion scheduler to:
     * <ul>
     *   <li>Transition {@code HARD_DELETE → AWAITING_PRUNE} and publish a cache-invalidation event</li>
     *   <li>Mark {@code SOFT_DELETE} apps as deleted on timeout</li>
     * </ul>
     */
    public record DeletionCandidate(UUID id, UUID applicationPartitionId, String name) {}

    /**
     * Inserts a new application including its sources JSONB.
     * The {@code applicationPartitionId} must be set on the entity before calling.
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ApplicationsEntity save(ApplicationsEntity entity, List<Map<String, Object>> sources) {
        return dsl.insertInto(APPLICATIONS)
                .set(APPLICATIONS.NAME, entity.getName())
                .set(APPLICATIONS.PROJECT_ID, entity.getProjectId())
                .set(APPLICATIONS.CLUSTER_ID, entity.getClusterId())
                .set(APPLICATIONS.APPLICATION_PARTITION_ID, entity.getApplicationPartitionId())
                .set(APPLICATIONS.STATUS, entity.getStatus())
                .set(APPLICATIONS.GENERATION, entity.getGeneration())
                .set(APPLICATIONS.SOURCES, jsonbUtils.toJsonb(sources))
                .returning()
                .fetchOneInto(ApplicationsEntity.class);
    }

    /**
     * Updates cluster_id, generation, and sources.
     * Name, partition assignment, and project_id are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ApplicationsEntity update(UUID id, ApplicationsEntity entity, List<Map<String, Object>> sources) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.CLUSTER_ID, entity.getClusterId())
                .set(APPLICATIONS.GENERATION, entity.getGeneration())
                .set(APPLICATIONS.SOURCES, jsonbUtils.toJsonb(sources))
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.ID.eq(id))
                .returning()
                .fetchOneInto(ApplicationsEntity.class);
    }

    public Optional<ApplicationsEntity> findById(UUID id) {
        return dsl.selectFrom(APPLICATIONS)
                .where(APPLICATIONS.ID.eq(id))
                .fetchOptionalInto(ApplicationsEntity.class);
    }

    /**
     * Returns all active (non-deleted, non-deleting) applications ordered by name.
     *
     * <p>Excludes:
     * <ul>
     *   <li>Soft-tombstoned rows ({@code deleted_at IS NOT NULL}) — kept for audit only.</li>
     *   <li>Mid-deletion rows ({@code deletion_mode IS NOT NULL}) — apps in {@code SOFT_DELETE},
     *       {@code HARD_DELETE}, or {@code AWAITING_PRUNE} are invisible to the management API
     *       so callers do not encounter a confusing 409 "mutation during deletion" response.</li>
     * </ul>
     *
     * <p>NOTE: does not include the {@code sources} JSONB — call {@link #findAllSourcesMap()} separately.
     */
    public List<ApplicationsEntity> findAll() {
        return dsl.selectFrom(APPLICATIONS)
                .where(APPLICATIONS.DELETED_AT.isNull())
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .orderBy(APPLICATIONS.NAME)
                .fetchInto(ApplicationsEntity.class);
    }

    /**
     * Returns a map of application id → raw sources JSONB for all active (non-deleted, non-deleting)
     * applications. Used together with {@link #findAll()} to avoid an N+1 query when building responses.
     *
     * <p>Applies the same visibility filter as {@link #findAll()}: excludes tombstoned rows
     * ({@code deleted_at IS NOT NULL}) and mid-deletion rows ({@code deletion_mode IS NOT NULL}).
     */
    public Map<UUID, JSONB> findAllSourcesMap() {
        return dsl.select(APPLICATIONS.ID, APPLICATIONS.SOURCES)
                .from(APPLICATIONS)
                .where(APPLICATIONS.DELETED_AT.isNull())
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .fetch()
                .stream()
                .filter(r -> r.get(APPLICATIONS.SOURCES) != null)
                .collect(java.util.stream.Collectors.toMap(
                        r -> r.get(APPLICATIONS.ID),
                        r -> r.get(APPLICATIONS.SOURCES)));
    }

    /**
     * Returns {@code true} if the application has an active deletion in progress or is
     * already soft-tombstoned. Callers use this to enforce the "no mutations during deletion"
     * invariant.
     *
     * <p>Guard condition: {@code deletion_mode IS NOT NULL OR deleted_at IS NOT NULL}.
     */
    public boolean isBeingDeleted(UUID id) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(APPLICATIONS)
                        .where(APPLICATIONS.ID.eq(id))
                        .and(APPLICATIONS.DELETION_MODE.isNotNull().or(APPLICATIONS.DELETED_AT.isNotNull())));
    }

    /**
     * Updates the status of an application directly by name.
     * Skips applications that are in a deletion state ({@code deletion_mode IS NOT NULL})
     * or already tombstoned ({@code deleted_at IS NOT NULL}) to avoid resurrecting stale status
     * from ArgoCD sync notifications that arrive after deletion is initiated.
     *
     * <p>This method intentionally does NOT publish any {@code PartitionChangedEvent}
     * to avoid triggering a reconcile loop (sync → notify → status update → notify → …).
     *
     * @return the number of rows updated (0 if no application with that name exists or
     *         if the application is in a deletion state)
     */
    public int updateStatusByName(String name, String status) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.STATUS, status)
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.NAME.eq(name))
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Updates {@code sync_status} and {@code health_status} for an application by name,
     * scoped to the control plane that issued the notification.
     *
     * <h3>Why CP-scoped?</h3>
     * During a failover the old control plane still manages the application and continues
     * to emit sync notifications. Without the CP filter those notifications would update
     * {@code updated_at}, satisfying the timestamp gate ({@code updated_at > migrated_at})
     * and causing a false-positive SYNCED/HEALTHY confirmation on the wrong CP.
     * By joining {@code applications → clusters → control_planes} and filtering on
     * {@code control_planes.name = controlPlaneName}, notifications from a CP that no
     * longer owns the application's cluster match zero rows — no false positive.
     *
     * <p>Skips applications in a deletion state ({@code deletion_mode IS NOT NULL}) or
     * already tombstoned ({@code deleted_at IS NOT NULL}).
     *
     * <p>Does NOT update {@code applications.status} — that field is owned exclusively
     * by the {@code application-partition} sync event path
     * ({@link #updateStatusByPartitionNumberAndControlPlaneName}).
     *
     * @param name             the ArgoCD Application name (unique)
     * @param controlPlaneName the control-plane name from the notification payload
     * @param syncStatus       normalised sync state (from {@link com.argocd.platform.api.util.SyncStatus})
     * @param healthStatus     normalised health state (from {@link com.argocd.platform.api.util.HealthStatus})
     * @return rows updated (0 if not found, in deletion, or owned by a different CP)
     */
    public int updateSyncAndHealthStatusByName(
            String name, String controlPlaneName, String syncStatus, String healthStatus) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.SYNC_STATUS, syncStatus)
                .set(APPLICATIONS.HEALTH_STATUS, healthStatus)
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.NAME.eq(name))
                .and(APPLICATIONS.CLUSTER_ID.in(
                        DSL.select(CLUSTERS.ID)
                                .from(CLUSTERS)
                                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                                .where(CONTROL_PLANES.NAME.eq(controlPlaneName))))
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Sets {@code status = ACTIVE} for all non-deleting applications that belong to
     * the given partition and whose cluster is assigned to the named control plane.
     *
     * <h3>Purpose</h3>
     * Called when an {@code on-application-partition-synced} event confirms that the
     * application-partition ApplicationSet successfully deployed Application objects
     * to the destination control plane. This is the <em>CREATED</em> confirmation
     * signal for failover operations: the Application object now exists on the new CP.
     *
     * <h3>CP-scoped join</h3>
     * {@code applications → clusters → control_planes} ensures that only applications
     * whose cluster currently points to {@code controlPlaneName} are updated — safe
     * to call even during a failover where some clusters may still be on the source CP.
     *
     * @param partitionNumber  the application partition number from the notification
     * @param controlPlaneName the control-plane name from the notification
     * @param status           the status to set (typically {@code "ACTIVE"})
     * @return rows updated (0 if the partition or CP does not exist)
     */
    public int updateStatusByPartitionNumberAndControlPlaneName(
            int partitionNumber, String controlPlaneName, String status) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.STATUS, status)
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.APPLICATION_PARTITION_ID.in(
                        DSL.select(APPLICATION_PARTITIONS.ID)
                                .from(APPLICATION_PARTITIONS)
                                .where(APPLICATION_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))))
                .and(APPLICATIONS.CLUSTER_ID.in(
                        DSL.select(CLUSTERS.ID)
                                .from(CLUSTERS)
                                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                                .where(CONTROL_PLANES.NAME.eq(controlPlaneName))))
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Sets {@code deletion_mode} on an application to begin the deletion state machine.
     * Also bumps {@code updated_at} which the scheduler fallback uses as the timeout anchor.
     *
     * <p>For hard-delete, {@code deletionPartitionGeneration} must be set to the value
     * returned by {@link com.argocd.platform.api.service.PartitionService#bumpApplicationPartitionGeneration}
     * called in the same transaction. The status service compares incoming
     * {@code application-partition-{N}-{cp}} sync notifications against this value to confirm
     * the finalizer-bearing manifest was synced before advancing to {@code AWAITING_PRUNE}.
     *
     * @param id                          the application ID
     * @param deletionMode                {@code "SOFT_DELETE"} or {@code "HARD_DELETE"}
     * @param deletionPartitionGeneration the partition generation at which the HARD_DELETE
     *                                    manifest was committed; {@code null} for SOFT_DELETE
     * @return rows updated (0 if the application does not exist or is already in deletion)
     */
    public int initiateDeletion(UUID id, String deletionMode, Long deletionPartitionGeneration) {
        var step = dsl.update(APPLICATIONS)
                .set(APPLICATIONS.DELETION_MODE, deletionMode)
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime());
        if (deletionPartitionGeneration != null) {
            step = step.set(APPLICATIONS.DELETION_PARTITION_GENERATION, deletionPartitionGeneration);
        }
        return step
                .where(APPLICATIONS.ID.eq(id))
                .and(APPLICATIONS.DELETION_MODE.isNull())    // guard: not already in deletion
                .and(APPLICATIONS.DELETED_AT.isNull())       // guard: not already tombstoned
                .execute();
    }

    /**
     * Atomically transitions {@code deletion_mode} from {@code fromMode} to {@code toMode}.
     * Used by the scheduler to advance {@code HARD_DELETE → AWAITING_PRUNE}.
     *
     * @return rows updated (0 if the application was not in {@code fromMode})
     */
    public int transitionDeletionMode(UUID id, String fromMode, String toMode) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.DELETION_MODE, toMode)
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.ID.eq(id))
                .and(APPLICATIONS.DELETION_MODE.eq(fromMode))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Sets {@code deleted_at = now()} and clears {@code deletion_mode} for an application
     * identified by name. Called when the ArgoCD {@code on-deleted} notification arrives
     * (both soft and hard delete paths) or on soft-delete scheduler timeout.
     *
     * <p>The WHERE guard ({@code deleted_at IS NULL}) makes this call idempotent —
     * duplicate {@code on-deleted} events are silently discarded.
     *
     * @param applicationName the ArgoCD Application name (unique after v1.0.5)
     * @return rows updated (0 if already tombstoned or not found)
     */
    public int markDeleted(String applicationName) {
        return dsl.update(APPLICATIONS)
                .setNull(APPLICATIONS.DELETION_MODE)
                .set(APPLICATIONS.DELETED_AT, DSL.currentLocalDateTime())
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.NAME.eq(applicationName))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Returns all applications in the given {@code deletionMode} whose {@code updated_at}
     * is older than {@code cutoff} and have not yet been tombstoned.
     *
     * <p>Used by the deletion scheduler to identify:
     * <ul>
     *   <li>{@code HARD_DELETE} apps ready for transition to {@code AWAITING_PRUNE}</li>
     *   <li>{@code SOFT_DELETE} apps that missed the {@code on-deleted} notification (timeout)</li>
     * </ul>
     */
    public List<DeletionCandidate> findByDeletionModeOlderThan(String deletionMode, LocalDateTime cutoff) {
        return dsl.select(APPLICATIONS.ID, APPLICATIONS.APPLICATION_PARTITION_ID, APPLICATIONS.NAME)
                .from(APPLICATIONS)
                .where(APPLICATIONS.DELETION_MODE.eq(deletionMode))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .and(APPLICATIONS.UPDATED_AT.lessThan(cutoff))
                .fetch()
                .stream()
                .map(r -> new DeletionCandidate(
                        r.get(APPLICATIONS.ID),
                        r.get(APPLICATIONS.APPLICATION_PARTITION_ID),
                        r.get(APPLICATIONS.NAME)))
                .toList();
    }

    /**
     * Finds all applications in {@code HARD_DELETE} state for the given partition
     * on the given control plane whose {@code deletion_partition_generation} is
     * less than or equal to {@code syncedGeneration}.
     *
     * <p>Called by the status service when an {@code on-application-partition-synced}
     * notification arrives for {@code application-partition-{N}-{cp}}. A generation value
     * of {@code m} in the notification means every application manifest that was rendered
     * at partition generation ≤ m — including those with {@code hardDelete: true} — has
     * been successfully applied to the control plane.  It is therefore safe to advance
     * matching applications to {@code AWAITING_PRUNE}.
     *
     * <p>The join path is:
     * {@code applications → application_partitions} (for partition number),
     * {@code applications → clusters → control_planes} (for control plane name).
     *
     * <p>Only applications with {@code deleted_at IS NULL} and
     * {@code deletion_partition_generation IS NOT NULL} are returned —
     * the latter guards against apps that had {@code deletion_partition_generation}
     * never set (e.g. enrolled before v1.0.7 was deployed).
     *
     * @param partitionNumber the application partition number from the notification
     * @param controlPlane    the control-plane name from the notification
     * @param syncedGeneration the generation value carried in the sync notification label
     * @return list of candidates whose state machine should advance to AWAITING_PRUNE
     */
    public List<DeletionCandidate> findHardDeleteByPartitionNumberAndCpUpToGeneration(
            int partitionNumber, String controlPlane, long syncedGeneration) {
        return dsl.select(APPLICATIONS.ID, APPLICATIONS.APPLICATION_PARTITION_ID, APPLICATIONS.NAME)
                .from(APPLICATIONS)
                .join(APPLICATION_PARTITIONS)
                        .on(APPLICATION_PARTITIONS.ID.eq(APPLICATIONS.APPLICATION_PARTITION_ID))
                .join(CLUSTERS).on(CLUSTERS.ID.eq(APPLICATIONS.CLUSTER_ID))
                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .where(APPLICATION_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .and(CONTROL_PLANES.NAME.eq(controlPlane))
                .and(APPLICATIONS.DELETION_MODE.eq(DeletionMode.HARD_DELETE.name()))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .and(APPLICATIONS.DELETION_PARTITION_GENERATION.isNotNull())
                .and(APPLICATIONS.DELETION_PARTITION_GENERATION.lessOrEqual(syncedGeneration))
                .fetch()
                .stream()
                .map(r -> new DeletionCandidate(
                        r.get(APPLICATIONS.ID),
                        r.get(APPLICATIONS.APPLICATION_PARTITION_ID),
                        r.get(APPLICATIONS.NAME)))
                .toList();
    }

    /**
     * Resets {@code sync_status} and {@code health_status} to {@code UNKNOWN} for all
     * active (non-deleted, non-deleting) applications whose cluster is in {@code clusterIds}.
     *
     * <h3>Why this is needed</h3>
     * When a cluster's {@code control_plane_id} is updated (failover migration step), the
     * old control plane continues to emit sync/health notifications for a brief overlap
     * period. Without a reset, the old status values could satisfy the success condition
     * and falsely confirm the cluster. Resetting to {@code UNKNOWN} ensures:
     * <ul>
     *   <li>the {@code sync_status}/{@code health_status} fields do not carry stale values
     *       from the source CP, and</li>
     *   <li>the timestamp gate ({@code applications.updated_at > migrated_at}) fires correctly
     *       because both this reset and {@code migrated_at} use {@code CURRENT_TIMESTAMP}
     *       within the same transaction — making them equal — so the gate is {@code false}
     *       until a real ArgoCD event from the <em>target</em> CP arrives.</li>
     * </ul>
     *
     * <p>Skips applications that are being deleted ({@code deletion_mode IS NOT NULL}) or
     * already tombstoned ({@code deleted_at IS NOT NULL}) — their status does not matter
     * for confirmation.
     *
     * @param clusterIds list of cluster IDs whose applications should be reset
     * @return number of application rows updated
     */
    public int resetSyncAndHealthStatusForClusters(List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return 0;
        }
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.SYNC_STATUS, "UNKNOWN")
                .set(APPLICATIONS.HEALTH_STATUS, "UNKNOWN")
                .set(APPLICATIONS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONS.CLUSTER_ID.in(clusterIds))
                .and(APPLICATIONS.DELETION_MODE.isNull())
                .and(APPLICATIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Deletes an application by id.
     * Callers must verify existence before calling this method.
     *
     * @deprecated Use {@link #initiateDeletion(UUID, String, Long)} instead. Hard physical deletion
     *             is replaced by the soft-tombstone + deletion state machine in v1.0.6.
     *             This method is retained only for any remaining internal use that predates
     *             the deletion state machine.
     */
    @Deprecated
    public void deleteById(UUID id) {
        dsl.deleteFrom(APPLICATIONS)
                .where(APPLICATIONS.ID.eq(id))
                .execute();
    }

}
