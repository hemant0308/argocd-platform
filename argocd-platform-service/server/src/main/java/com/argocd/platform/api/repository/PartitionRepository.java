package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.model.response.argocd.ApplicationSetPartitionResponse;
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
import static com.argocd.platform.db.jooq.Tables.APPLICATIONSET_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONSETS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTER_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECT_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

/**
 * Handles stable partition assignment for all resource types.
 *
 * <p><b>Option A — global resource-level partitions</b>: {@code cluster_partitions},
 * {@code application_partitions}, and {@code project_partitions} each have a globally
 * unique {@code partition_number}. Partition numbers are unique across all control planes.
 *
 * <p><b>Architectural rule (permanent):</b> Control planes are stateless. Only clusters
 * have a relationship with control planes. All other resources (partitions) are derived
 * from the cluster → control-plane relationship at query time — never stored as a column.
 *
 * <p><b>Assignment algorithm (SELECT FOR UPDATE, runs inside a transaction)</b>:
 * <ol>
 *   <li>Lock all existing partitions for the type ordered by partition_number.</li>
 *   <li>For each partition, count how many resources are currently assigned.</li>
 *   <li>Return the first partition whose count is below {@code targetSize}.</li>
 *   <li>If all existing partitions are full (or none exist), create a new one with
 *       partition_number = MAX(existing) + 1 and return its id.</li>
 *   <li>Bump the assigned partition's {@code generation} to signal a desired-state
 *       change to the ApplicationSet Plugin Generator.</li>
 * </ol>
 */
@Repository
@RequiredArgsConstructor
public class PartitionRepository {

    private final DSLContext dsl;

    // =========================================================================
    // Global partition resolution — all types
    // =========================================================================

    /**
     * Resolves (or creates) a partition for the given type.
     * All three types (CLUSTER, APPLICATION, PROJECT) use globally-unique partition numbers.
     *
     * @param type       the partition type
     * @param targetSize max resources per partition before creating a new one
     * @return UUID of the assigned partition
     */
    @Transactional
    public UUID resolvePartitionId(PartitionType type, int targetSize) {
        return switch (type) {
            case PROJECT         -> resolveProjectPartition(targetSize);
            case CLUSTER         -> resolveClusterPartition(targetSize);
            case APPLICATION     -> resolveApplicationPartition(targetSize);
            case APPLICATION_SET -> resolveApplicationSetPartition(targetSize);
        };
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
    // Cluster partitions (global — Option A)
    // =========================================================================

    private UUID resolveClusterPartition(int targetSize) {
        List<UUID> partitionIds = dsl
                .select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
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

        return createClusterPartition();
    }

    private void bumpClusterPartitionGeneration(UUID partitionId) {
        dsl.update(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.GENERATION, CLUSTER_PARTITIONS.GENERATION.add(1))
                .where(CLUSTER_PARTITIONS.ID.eq(partitionId))
                .execute();
    }

    private UUID createClusterPartition() {
        Integer maxNum = dsl
                .select(DSL.max(CLUSTER_PARTITIONS.PARTITION_NUMBER))
                .from(CLUSTER_PARTITIONS)
                .fetchOne(DSL.max(CLUSTER_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(CLUSTER_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(CLUSTER_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(CLUSTER_PARTITIONS.ID);
    }

    // =========================================================================
    // Application partitions (global — Option A)
    // =========================================================================

    private UUID resolveApplicationPartition(int targetSize) {
        List<UUID> partitionIds = dsl
                .select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
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

        return createApplicationPartition();
    }

    /**
     * Atomically increments {@code application_partitions.generation} by 1 and returns
     * the new value via PostgreSQL {@code RETURNING}. Used whenever the partition's
     * desired state changes (app create, update, soft-delete, or hard-delete initiation)
     * to advance the monotonic version counter.
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

    private UUID createApplicationPartition() {
        Integer maxNum = dsl
                .select(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER))
                .from(APPLICATION_PARTITIONS)
                .fetchOne(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(APPLICATION_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(APPLICATION_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(APPLICATION_PARTITIONS.ID);
    }

    // =========================================================================
    // ApplicationSet partitions (global — same pattern as Application)
    // =========================================================================

    private UUID resolveApplicationSetPartition(int targetSize) {
        List<UUID> partitionIds = dsl
                .select(APPLICATIONSET_PARTITIONS.ID)
                .from(APPLICATIONSET_PARTITIONS)
                .orderBy(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(APPLICATIONSET_PARTITIONS.ID);

        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(
                    APPLICATIONSETS, APPLICATIONSETS.APPLICATIONSET_PARTITION_ID.eq(partitionId));
            if (count < targetSize) {
                bumpApplicationSetPartitionGeneration(partitionId);
                return partitionId;
            }
        }

        return createApplicationSetPartition();
    }

    /**
     * Atomically increments {@code applicationset_partitions.generation} by 1 and returns
     * the new value. Used whenever the partition's desired state changes.
     *
     * @return the new generation value after the increment
     */
    public long bumpAndReturnApplicationSetPartitionGeneration(UUID partitionId) {
        var record = dsl.update(APPLICATIONSET_PARTITIONS)
                .set(APPLICATIONSET_PARTITIONS.GENERATION,
                        APPLICATIONSET_PARTITIONS.GENERATION.add(1))
                .where(APPLICATIONSET_PARTITIONS.ID.eq(partitionId))
                .returning(APPLICATIONSET_PARTITIONS.GENERATION)
                .fetchOne();
        return record != null ? record.get(APPLICATIONSET_PARTITIONS.GENERATION) : 0L;
    }

    private void bumpApplicationSetPartitionGeneration(UUID partitionId) {
        bumpAndReturnApplicationSetPartitionGeneration(partitionId);
    }

    private UUID createApplicationSetPartition() {
        Integer maxNum = dsl
                .select(DSL.max(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER))
                .from(APPLICATIONSET_PARTITIONS)
                .fetchOne(DSL.max(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        return dsl.insertInto(APPLICATIONSET_PARTITIONS)
                .set(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(APPLICATIONSET_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(APPLICATIONSET_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(APPLICATIONSET_PARTITIONS.ID);
    }

    // =========================================================================
    // Batch generation bumps — used by FailoverBatchService after migration
    // =========================================================================

    /**
     * Bumps generation on a set of cluster partition IDs in a single UPDATE.
     * Called after {@code migrateBatch()} to invalidate cluster partitions' caches.
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
     * Called after {@code migrateBatch()} to invalidate application partitions' caches.
     */
    @Transactional
    public void bumpApplicationPartitionGenerations(Set<UUID> partitionIds) {
        if (partitionIds.isEmpty()) return;
        dsl.update(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.GENERATION, APPLICATION_PARTITIONS.GENERATION.add(1))
                .where(APPLICATION_PARTITIONS.ID.in(partitionIds))
                .execute();
    }

    /**
     * Bumps generation on a set of project partition IDs in a single UPDATE.
     */
    @Transactional
    public void bumpProjectPartitionGenerations(Set<UUID> partitionIds) {
        if (partitionIds.isEmpty()) return;
        dsl.update(PROJECT_PARTITIONS)
                .set(PROJECT_PARTITIONS.GENERATION, PROJECT_PARTITIONS.GENERATION.add(1))
                .where(PROJECT_PARTITIONS.ID.in(partitionIds))
                .execute();
    }

    /**
     * Bumps generation on a set of applicationset partition IDs in a single UPDATE.
     */
    @Transactional
    public void bumpApplicationSetPartitionGenerations(Set<UUID> partitionIds) {
        if (partitionIds.isEmpty()) return;
        dsl.update(APPLICATIONSET_PARTITIONS)
                .set(APPLICATIONSET_PARTITIONS.GENERATION,
                        APPLICATIONSET_PARTITIONS.GENERATION.add(1))
                .where(APPLICATIONSET_PARTITIONS.ID.in(partitionIds))
                .execute();
    }

    // =========================================================================
    // ArgoCD read-side: partition UUID / number resolution
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
            case APPLICATION_SET -> dsl.select(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER)
                    .from(APPLICATIONSET_PARTITIONS)
                    .where(APPLICATIONSET_PARTITIONS.ID.eq(id))
                    .fetchOptional(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER);
        };
    }

    /**
     * Resolves a cluster partition UUID by its global partition number.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findClusterPartitionIdByNumber(int partitionNumber) {
        return dsl.select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
                .where(CLUSTER_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(CLUSTER_PARTITIONS.ID);
    }

    /**
     * Resolves an application partition UUID by its global partition number.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findApplicationPartitionIdByNumber(int partitionNumber) {
        return dsl.select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(APPLICATION_PARTITIONS.ID);
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
     * Resolves an applicationset partition UUID by its global partition number.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findApplicationSetPartitionIdByNumber(int partitionNumber) {
        return dsl.select(APPLICATIONSET_PARTITIONS.ID)
                .from(APPLICATIONSET_PARTITIONS)
                .where(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(APPLICATIONSET_PARTITIONS.ID);
    }

    /**
     * Returns the current generation of an application partition.
     * Used by the plugin service to include the generation in each
     * {@code application-groups} response entry.
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

    /**
     * Returns the current generation of an applicationset partition.
     * Used by the plugin service to include the generation in each
     * {@code applicationset-groups} response entry.
     *
     * @return current generation, or 0 if the partition does not exist
     */
    @Transactional(readOnly = true)
    public long findApplicationSetPartitionGeneration(UUID partitionId) {
        Long gen = dsl.select(APPLICATIONSET_PARTITIONS.GENERATION)
                .from(APPLICATIONSET_PARTITIONS)
                .where(APPLICATIONSET_PARTITIONS.ID.eq(partitionId))
                .fetchOne(APPLICATIONSET_PARTITIONS.GENERATION);
        return gen != null ? gen : 0L;
    }

    // =========================================================================
    // ArgoCD read-side: partition list with resource counts (plugin responses)
    // =========================================================================

    /**
     * Returns all project partitions with the count of projects currently assigned to each.
     * Empty partitions are included with {@code projectCount = 0} (LEFT JOIN + COUNT(id)).
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
     * Returns all cluster partitions with the count of clusters currently assigned to each.
     * Ordered by partition_number. Globally partitioned (Option A — no CP association).
     */
    @Transactional(readOnly = true)
    public List<ClusterPartitionResponse> findAllClusterPartitions() {
        Field<Integer> clusterCount = DSL.count(CLUSTERS.ID).as("cluster_count");
        return dsl.select(
                        CLUSTER_PARTITIONS.ID,
                        CLUSTER_PARTITIONS.PARTITION_NUMBER,
                        CLUSTER_PARTITIONS.GENERATION,
                        clusterCount)
                .from(CLUSTER_PARTITIONS)
                .leftJoin(CLUSTERS).on(CLUSTERS.CLUSTER_PARTITION_ID.eq(CLUSTER_PARTITIONS.ID))
                .groupBy(CLUSTER_PARTITIONS.ID,
                        CLUSTER_PARTITIONS.PARTITION_NUMBER,
                        CLUSTER_PARTITIONS.GENERATION)
                .orderBy(CLUSTER_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ClusterPartitionResponse.builder()
                        .id(r.get(CLUSTER_PARTITIONS.ID))
                        .partitionNumber(r.get(CLUSTER_PARTITIONS.PARTITION_NUMBER))
                        .generation(r.get(CLUSTER_PARTITIONS.GENERATION))
                        .clusterCount(r.get(clusterCount))
                        .build());
    }

    /**
     * Returns all applicationset partitions with the count of applicationsets assigned to each.
     * Ordered by partition_number. Globally partitioned (Option A — no CP association).
     */
    @Transactional(readOnly = true)
    public List<ApplicationSetPartitionResponse> findAllApplicationSetPartitions() {
        Field<Integer> appSetCount = DSL.count(APPLICATIONSETS.ID).as("applicationset_count");
        return dsl.select(
                        APPLICATIONSET_PARTITIONS.ID,
                        APPLICATIONSET_PARTITIONS.PARTITION_NUMBER,
                        APPLICATIONSET_PARTITIONS.GENERATION,
                        appSetCount)
                .from(APPLICATIONSET_PARTITIONS)
                .leftJoin(APPLICATIONSETS)
                        .on(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID
                                .eq(APPLICATIONSET_PARTITIONS.ID))
                .groupBy(APPLICATIONSET_PARTITIONS.ID,
                        APPLICATIONSET_PARTITIONS.PARTITION_NUMBER,
                        APPLICATIONSET_PARTITIONS.GENERATION)
                .orderBy(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ApplicationSetPartitionResponse.builder()
                        .id(r.get(APPLICATIONSET_PARTITIONS.ID))
                        .partitionNumber(r.get(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER))
                        .generation(r.get(APPLICATIONSET_PARTITIONS.GENERATION))
                        .applicationSetCount(r.get(appSetCount))
                        .build());
    }

    /**
     * Returns all application partitions with the count of applications currently assigned.
     * Ordered by partition_number. Globally partitioned (Option A — no CP association).
     */
    @Transactional(readOnly = true)
    public List<ApplicationPartitionResponse> findAllApplicationPartitions() {
        Field<Integer> applicationCount = DSL.count(APPLICATIONS.ID).as("application_count");
        return dsl.select(
                        APPLICATION_PARTITIONS.ID,
                        APPLICATION_PARTITIONS.PARTITION_NUMBER,
                        APPLICATION_PARTITIONS.GENERATION,
                        applicationCount)
                .from(APPLICATION_PARTITIONS)
                .leftJoin(APPLICATIONS).on(APPLICATIONS.APPLICATION_PARTITION_ID.eq(APPLICATION_PARTITIONS.ID))
                .groupBy(APPLICATION_PARTITIONS.ID,
                        APPLICATION_PARTITIONS.PARTITION_NUMBER,
                        APPLICATION_PARTITIONS.GENERATION)
                .orderBy(APPLICATION_PARTITIONS.PARTITION_NUMBER)
                .fetch(r -> ApplicationPartitionResponse.builder()
                        .id(r.get(APPLICATION_PARTITIONS.ID))
                        .partitionNumber(r.get(APPLICATION_PARTITIONS.PARTITION_NUMBER))
                        .generation(r.get(APPLICATION_PARTITIONS.GENERATION))
                        .applicationCount(r.get(applicationCount))
                        .build());
    }
}
