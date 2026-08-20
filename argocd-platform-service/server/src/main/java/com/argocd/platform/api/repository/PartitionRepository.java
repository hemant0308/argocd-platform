package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ClusterPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ProjectPartitionResponse;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTER_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;
import static com.argocd.platform.db.jooq.Tables.PROJECT_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

/**
 * Handles stable partition assignment for all resource types.
 *
 * <p><b>Option B — CP-scoped partitions</b>: {@code cluster_partitions} and
 * {@code application_partitions} now each belong to exactly one control plane
 * ({@code control_plane_id NOT NULL}). Partition numbers are unique per-CP, not globally.
 * Use the CP-scoped methods ({@code resolveClusterPartitionForCp},
 * {@code resolveApplicationPartitionForCp}) for cluster and application assignment.
 *
 * <p>{@code project_partitions} remains global — AppProjects must exist on every CP
 * that hosts the project's clusters, so they are not CP-scoped.
 *
 * <p><b>Assignment algorithm (SELECT FOR UPDATE, runs inside a transaction)</b>:
 * <ol>
 *   <li>Lock all existing partitions for the type / CP ordered by partition_number.</li>
 *   <li>For each partition, count how many resources are currently assigned.</li>
 *   <li>Return the first partition whose count is below {@code targetSize}.</li>
 *   <li>If all existing partitions are full (or none exist), create a new one with
 *       partition_number = MAX(existing within that CP) + 1 and return its id.</li>
 *   <li>Bump the assigned partition's {@code generation} to signal a desired-state
 *       change to the ApplicationSet Plugin Generator.</li>
 * </ol>
 */
@Repository
@RequiredArgsConstructor
public class PartitionRepository {

    private final DSLContext dsl;

    // =========================================================================
    // Global partition resolution (project only — CLUSTER/APPLICATION are CP-scoped)
    // =========================================================================

    /**
     * Resolves (or creates) a project partition. Project partitions remain global.
     * For cluster and application partitions, use the CP-scoped overloads.
     *
     * @param type       must be {@link PartitionType#PROJECT}; CLUSTER/APPLICATION throw
     * @param targetSize max resources per partition before creating a new one
     * @return UUID of the assigned partition
     */
    @Transactional
    public UUID resolvePartitionId(PartitionType type, int targetSize) {
        return switch (type) {
            case PROJECT     -> resolveProjectPartition(targetSize);
            case CLUSTER, APPLICATION -> throw new UnsupportedOperationException(
                    "Use resolveClusterPartitionForCp / resolveApplicationPartitionForCp " +
                    "for CP-scoped partition types (Option B).");
        };
    }

    // =========================================================================
    // CP-scoped cluster partition resolution
    // =========================================================================

    /**
     * Resolves (or creates) a cluster partition scoped to the given control plane.
     * Runs inside a transaction with SELECT FOR UPDATE to prevent duplicate creation.
     *
     * @param cpId       the target control plane
     * @param targetSize max clusters per partition before creating a new one
     * @return UUID of the assigned CP-scoped cluster partition
     */
    @Transactional
    public UUID resolveClusterPartitionForCp(UUID cpId, int targetSize) {
        // Lock all partitions for this CP; order is deterministic to avoid deadlocks
        List<UUID> partitionIds = dsl
                .select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
                .where(CLUSTER_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .orderBy(CLUSTER_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(CLUSTER_PARTITIONS.ID);

        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(CLUSTERS, CLUSTERS.CLUSTER_PARTITION_ID.eq(partitionId));
            if (count < targetSize) {
                bumpClusterPartitionGeneration(partitionId);
                return partitionId;
            }
        }

        return createClusterPartitionForCp(cpId);
    }

    /**
     * Resolves (or creates) an application partition scoped to the given control plane.
     * Prefer {@link #findApplicationPartitionForCluster} first to maintain cluster-locality.
     *
     * @param cpId       the target control plane
     * @param targetSize max applications per partition before creating a new one
     * @return UUID of the assigned CP-scoped application partition
     */
    @Transactional
    public UUID resolveApplicationPartitionForCp(UUID cpId, int targetSize) {
        List<UUID> partitionIds = dsl
                .select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .orderBy(APPLICATION_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(APPLICATION_PARTITIONS.ID);

        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(
                    APPLICATIONS, APPLICATIONS.APPLICATION_PARTITION_ID.eq(partitionId));
            if (count < targetSize) {
                bumpApplicationPartitionGeneration(partitionId);
                return partitionId;
            }
        }

        return createApplicationPartitionForCp(cpId);
    }

    /**
     * Cluster-locality lookup: returns the application partition on {@code targetCpId} that
     * already holds applications from {@code clusterId}, if any.
     *
     * <p>Used by {@code FailoverBatchService} to reuse an existing target partition on retry
     * (idempotency) and to keep all apps from the same cluster together in one partition.
     * Returns empty when the cluster has no apps on that CP yet, in which case the caller
     * should fall back to {@link #resolveApplicationPartitionForCp}.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findApplicationPartitionForCluster(UUID clusterId, UUID targetCpId) {
        return dsl
                .select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .join(APPLICATIONS).on(APPLICATIONS.APPLICATION_PARTITION_ID.eq(APPLICATION_PARTITIONS.ID))
                .where(APPLICATIONS.CLUSTER_ID.eq(clusterId))
                .and(APPLICATION_PARTITIONS.CONTROL_PLANE_ID.eq(targetCpId))
                .and(APPLICATIONS.DELETED_AT.isNull())
                .limit(1)
                .fetchOptional(APPLICATION_PARTITIONS.ID);
    }

    // =========================================================================
    // CP-aware by-(cpId, partitionNumber) lookups
    // =========================================================================

    /**
     * Resolves a cluster partition UUID by CP id and partition number.
     * Under Option B, partition numbers are unique per-CP — cpId is required to
     * disambiguate. Used by {@code PartitionService} to populate the forward cache.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findClusterPartitionIdByCpAndNumber(UUID cpId, int partitionNumber) {
        return dsl
                .select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
                .where(CLUSTER_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .and(CLUSTER_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(CLUSTER_PARTITIONS.ID);
    }

    /**
     * Resolves an application partition UUID by CP id and partition number.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findApplicationPartitionIdByCpAndNumber(UUID cpId, int partitionNumber) {
        return dsl
                .select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .and(APPLICATION_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(APPLICATION_PARTITIONS.ID);
    }

    // =========================================================================
    // Project partitions (global — unchanged)
    // =========================================================================

    private UUID resolveProjectPartition(int targetSize) {
        List<UUID> partitionIds = dsl
                .select(PROJECT_PARTITIONS.ID)
                .from(PROJECT_PARTITIONS)
                .orderBy(PROJECT_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(PROJECT_PARTITIONS.ID);

        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(PROJECTS, PROJECTS.PROJECT_PARTITION_ID.eq(partitionId));
            if (count < targetSize) {
                bumpProjectPartitionGeneration(partitionId);
                return partitionId;
            }
        }

        return createProjectPartition();
    }

    private void bumpProjectPartitionGeneration(UUID partitionId) {
        dsl.update(PROJECT_PARTITIONS)
                .set(PROJECT_PARTITIONS.GENERATION, PROJECT_PARTITIONS.GENERATION.add(1))
                .where(PROJECT_PARTITIONS.ID.eq(partitionId))
                .execute();
    }

    private UUID createProjectPartition() {
        Integer maxNum = dsl
                .select(DSL.max(PROJECT_PARTITIONS.PARTITION_NUMBER))
                .from(PROJECT_PARTITIONS)
                .fetchOne(DSL.max(PROJECT_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(PROJECT_PARTITIONS)
                .set(PROJECT_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(PROJECT_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(PROJECT_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(PROJECT_PARTITIONS.ID);
    }

    // =========================================================================
    // Private helpers — cluster partitions (CP-scoped)
    // =========================================================================

    private void bumpClusterPartitionGeneration(UUID partitionId) {
        dsl.update(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.GENERATION, CLUSTER_PARTITIONS.GENERATION.add(1))
                .where(CLUSTER_PARTITIONS.ID.eq(partitionId))
                .execute();
    }

    private UUID createClusterPartitionForCp(UUID cpId) {
        // partition_number is unique per-CP (not globally) under Option B
        Integer maxNum = dsl
                .select(DSL.max(CLUSTER_PARTITIONS.PARTITION_NUMBER))
                .from(CLUSTER_PARTITIONS)
                .where(CLUSTER_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .fetchOne(DSL.max(CLUSTER_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(CLUSTER_PARTITIONS.CONTROL_PLANE_ID, cpId)
                .set(CLUSTER_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(CLUSTER_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(CLUSTER_PARTITIONS.ID);
    }

    // =========================================================================
    // Private helpers — application partitions (CP-scoped)
    // =========================================================================

    /**
     * Atomically increments {@code application_partitions.generation} by 1 and returns
     * the new value via PostgreSQL {@code RETURNING}. Used whenever the partition's
     * desired state changes (app create, update, soft-delete, or hard-delete initiation)
     * to advance the monotonic version counter carried in the
     * {@code application-partition-{N}-{cp}} Application's labels.
     *
     * <p>For hard-delete: the caller stores the returned value in
     * {@code applications.deletion_partition_generation} so the status service can
     * confirm — without a fixed time delay — that the correct generation has been synced.
     *
     * @return the new generation value after the increment
     */
    public long bumpAndReturnApplicationPartitionGeneration(UUID partitionId) {
        var record = dsl.update(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.GENERATION, APPLICATION_PARTITIONS.GENERATION.add(1))
                .where(APPLICATION_PARTITIONS.ID.eq(partitionId))
                .returning(APPLICATION_PARTITIONS.GENERATION)
                .fetchOne();
        return record != null ? record.get(APPLICATION_PARTITIONS.GENERATION) : 0L;
    }

    private void bumpApplicationPartitionGeneration(UUID partitionId) {
        bumpAndReturnApplicationPartitionGeneration(partitionId);
    }

    private UUID createApplicationPartitionForCp(UUID cpId) {
        Integer maxNum = dsl
                .select(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER))
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.CONTROL_PLANE_ID.eq(cpId))
                .fetchOne(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(APPLICATION_PARTITIONS.CONTROL_PLANE_ID, cpId)
                .set(APPLICATION_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(APPLICATION_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(APPLICATION_PARTITIONS.ID);
    }

    // =========================================================================
    // Batch generation bumps — used by FailoverBatchService after migration
    // =========================================================================

    /**
     * Bumps generation on a set of cluster partition IDs in a single UPDATE.
     * Called after {@code migrateBatch()} to invalidate both source and target
     * cluster partitions' caches.
     */
    @Transactional
    public void bumpClusterPartitionGenerations(Set<UUID> partitionIds) {
        if (partitionIds.isEmpty()) return;
        dsl.update(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.GENERATION, CLUSTER_PARTITIONS.GENERATION.add(1))
                .where(CLUSTER_PARTITIONS.ID.in(partitionIds))
                .execute();
    }

    /**
     * Bumps generation on a set of application partition IDs in a single UPDATE.
     * Called after {@code migrateBatch()} to invalidate both source and target
     * application partitions' caches.
     */
    @Transactional
    public void bumpApplicationPartitionGenerations(Set<UUID> partitionIds) {
        if (partitionIds.isEmpty()) return;
        dsl.update(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.GENERATION, APPLICATION_PARTITIONS.GENERATION.add(1))
                .where(APPLICATION_PARTITIONS.ID.in(partitionIds))
                .execute();
    }

    // =========================================================================
    // ArgoCD read-side: partition number / UUID resolution
    // =========================================================================

    /**
     * Resolves a partition number by its UUID.
     * Used by {@link com.argocd.platform.api.service.PartitionService} to populate the
     * reverse cache when a new partition UUID is seen for the first time.
     *
     * @return the partition_number, or empty if no partition with that id exists
     */
    @Transactional(readOnly = true)
    public Optional<Integer> findPartitionNumberById(PartitionType type, UUID id) {
        return switch (type) {
            case CLUSTER -> dsl.select(CLUSTER_PARTITIONS.PARTITION_NUMBER)
                    .from(CLUSTER_PARTITIONS)
                    .where(CLUSTER_PARTITIONS.ID.eq(id))
                    .fetchOptional(CLUSTER_PARTITIONS.PARTITION_NUMBER);
            case PROJECT -> dsl.select(PROJECT_PARTITIONS.PARTITION_NUMBER)
                    .from(PROJECT_PARTITIONS)
                    .where(PROJECT_PARTITIONS.ID.eq(id))
                    .fetchOptional(PROJECT_PARTITIONS.PARTITION_NUMBER);
            case APPLICATION -> dsl.select(APPLICATION_PARTITIONS.PARTITION_NUMBER)
                    .from(APPLICATION_PARTITIONS)
                    .where(APPLICATION_PARTITIONS.ID.eq(id))
                    .fetchOptional(APPLICATION_PARTITIONS.PARTITION_NUMBER);
        };
    }

    /**
     * Resolves a project partition UUID by its human-readable partition number.
     * Returns empty if no partition with that number exists.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findProjectPartitionIdByNumber(int partitionNumber) {
        return dsl.select(PROJECT_PARTITIONS.ID)
                .from(PROJECT_PARTITIONS)
                .where(PROJECT_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(PROJECT_PARTITIONS.ID);
    }

    /**
     * Returns the current generation of an application partition.
     * Used by the plugin service to include the generation in each
     * {@code application-groups} response entry so ArgoCD can carry it
     * as a label on the generated {@code application-partition-{N}-{cp}} Application.
     *
     * @return current generation, or 0 if the partition does not exist
     */
    @Transactional(readOnly = true)
    public long findApplicationPartitionGeneration(UUID partitionId) {
        Long gen = dsl.select(APPLICATION_PARTITIONS.GENERATION)
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.ID.eq(partitionId))
                .fetchOne(APPLICATION_PARTITIONS.GENERATION);
        return gen != null ? gen : 0L;
    }

    // =========================================================================
    // ArgoCD read-side: partition list with resource counts (plugin responses)
    // =========================================================================

    /**
     * Returns all project partitions with the count of projects currently assigned to each.
     * Empty partitions are included with {@code projectCount = 0} (LEFT JOIN + COUNT(id)).
     * Project partitions are global (not CP-scoped).
     */
    @Transactional(readOnly = true)
    public List<ProjectPartitionResponse> findAllProjectPartitions() {
        Field<Integer> projectCount = DSL.count(PROJECTS.ID).as("project_count");
        return dsl.select(
                        PROJECT_PARTITIONS.ID,
                        PROJECT_PARTITIONS.PARTITION_NUMBER,
                        PROJECT_PARTITIONS.GENERATION,
                        projectCount)
                .from(PROJECT_PARTITIONS)
                .leftJoin(PROJECTS).on(PROJECTS.PROJECT_PARTITION_ID.eq(PROJECT_PARTITIONS.ID))
                .groupBy(PROJECT_PARTITIONS.ID,
                        PROJECT_PARTITIONS.PARTITION_NUMBER,
                        PROJECT_PARTITIONS.GENERATION)
                .orderBy(PROJECT_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ProjectPartitionResponse.builder()
                        .id(r.get(PROJECT_PARTITIONS.ID))
                        .partitionNumber(r.get(PROJECT_PARTITIONS.PARTITION_NUMBER))
                        .generation(r.get(PROJECT_PARTITIONS.GENERATION))
                        .projectCount(r.get(projectCount))
                        .build());
    }

    /**
     * Returns all cluster partitions with the count of clusters currently assigned to each,
     * including the owning control plane name (CP-scoped, Option B).
     * Ordered by (controlPlaneName, partitionNumber) so partitions group naturally by CP.
     */
    @Transactional(readOnly = true)
    public List<ClusterPartitionResponse> findAllClusterPartitions() {
        Field<Integer> clusterCount = DSL.count(CLUSTERS.ID).as("cluster_count");
        return dsl.select(
                        CLUSTER_PARTITIONS.ID,
                        CLUSTER_PARTITIONS.PARTITION_NUMBER,
                        CONTROL_PLANES.NAME,
                        CLUSTER_PARTITIONS.GENERATION,
                        clusterCount)
                .from(CLUSTER_PARTITIONS)
                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTER_PARTITIONS.CONTROL_PLANE_ID))
                .leftJoin(CLUSTERS).on(CLUSTERS.CLUSTER_PARTITION_ID.eq(CLUSTER_PARTITIONS.ID))
                .groupBy(CLUSTER_PARTITIONS.ID,
                        CLUSTER_PARTITIONS.PARTITION_NUMBER,
                        CLUSTER_PARTITIONS.GENERATION,
                        CONTROL_PLANES.NAME)
                .orderBy(CONTROL_PLANES.NAME, CLUSTER_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ClusterPartitionResponse.builder()
                        .id(r.get(CLUSTER_PARTITIONS.ID))
                        .partitionNumber(r.get(CLUSTER_PARTITIONS.PARTITION_NUMBER))
                        .controlPlaneName(r.get(CONTROL_PLANES.NAME))
                        .generation(r.get(CLUSTER_PARTITIONS.GENERATION))
                        .clusterCount(r.get(clusterCount))
                        .build());
    }

    /**
     * Returns all application partitions with the count of applications currently assigned,
     * including the owning control plane name (CP-scoped, Option B).
     * Ordered by (controlPlaneName, partitionNumber).
     */
    @Transactional(readOnly = true)
    public List<ApplicationPartitionResponse> findAllApplicationPartitions() {
        Field<Integer> applicationCount = DSL.count(APPLICATIONS.ID).as("application_count");
        return dsl.select(
                        APPLICATION_PARTITIONS.ID,
                        APPLICATION_PARTITIONS.PARTITION_NUMBER,
                        CONTROL_PLANES.NAME,
                        APPLICATION_PARTITIONS.GENERATION,
                        applicationCount)
                .from(APPLICATION_PARTITIONS)
                .join(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(APPLICATION_PARTITIONS.CONTROL_PLANE_ID))
                .leftJoin(APPLICATIONS).on(APPLICATIONS.APPLICATION_PARTITION_ID.eq(APPLICATION_PARTITIONS.ID))
                .groupBy(APPLICATION_PARTITIONS.ID,
                        APPLICATION_PARTITIONS.PARTITION_NUMBER,
                        APPLICATION_PARTITIONS.GENERATION,
                        CONTROL_PLANES.NAME)
                .orderBy(CONTROL_PLANES.NAME, APPLICATION_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ApplicationPartitionResponse.builder()
                        .id(r.get(APPLICATION_PARTITIONS.ID))
                        .partitionNumber(r.get(APPLICATION_PARTITIONS.PARTITION_NUMBER))
                        .controlPlaneName(r.get(CONTROL_PLANES.NAME))
                        .generation(r.get(APPLICATION_PARTITIONS.GENERATION))
                        .applicationCount(r.get(applicationCount))
                        .build());
    }
}
