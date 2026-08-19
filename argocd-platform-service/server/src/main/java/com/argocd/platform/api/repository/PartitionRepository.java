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
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTER_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECT_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

/**
 * Handles stable partition assignment for all resource types.
 *
 * <p>Algorithm (runs inside a transaction with SELECT FOR UPDATE):
 * <ol>
 *   <li>Lock all existing partitions for the type ordered by partition_number.</li>
 *   <li>For each partition, count how many resources are currently assigned.</li>
 *   <li>Return the first partition whose count is below {@code targetSize}.</li>
 *   <li>If all existing partitions are full (or none exist), create a new partition
 *       with partition_number = MAX(existing) + 1 and return its id.</li>
 *   <li>Increment the assigned partition's {@code generation} to signal a desired-state
 *       change to the ApplicationSet Plugin Generator.</li>
 * </ol>
 *
 * <p><b>Status note:</b> {@link ResourceStatus} currently only has {@code UNKNOWN};
 * partitions are created with {@code UNKNOWN} and queried without a status filter.
 * When {@code ACTIVE} / {@code INACTIVE} are added, the query should be updated to
 * {@code WHERE status = 'ACTIVE'} and partition creation should use {@code 'ACTIVE'}.
 */
@Repository
@RequiredArgsConstructor
public class PartitionRepository {

    private final DSLContext dsl;

    /**
     * Resolves (or creates) a partition for the given resource type.
     *
     * @param type       which resource dimension to partition (PROJECT, CLUSTER, APPLICATION)
     * @param targetSize max resources per partition before creating a new one
     * @return UUID of the assigned partition
     */
    @Transactional
    public UUID resolvePartitionId(PartitionType type, int targetSize) {
        return switch (type) {
            case CLUSTER     -> resolveClusterPartition(targetSize);
            case PROJECT     -> resolveProjectPartition(targetSize);
            case APPLICATION -> resolveApplicationPartition(targetSize);
        };
    }

    // -------------------------------------------------------------------------
    // Cluster partitions
    // -------------------------------------------------------------------------

    private UUID resolveClusterPartition(int targetSize) {
        // Step 1: lock all existing cluster partitions (prevents concurrent duplicate creation)
        List<UUID> partitionIds = dsl
                .select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
                .orderBy(CLUSTER_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(CLUSTER_PARTITIONS.ID);

        // Step 2: find first partition with available capacity
        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(CLUSTERS, CLUSTERS.CLUSTER_PARTITION_ID.eq(partitionId));
            if (count < targetSize) {
                bumpClusterPartitionGeneration(partitionId);
                return partitionId;
            }
        }

        // Step 3: all partitions full — create a new one
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

        UUID newId = dsl.insertInto(CLUSTER_PARTITIONS)
                .set(CLUSTER_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(CLUSTER_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(CLUSTER_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(CLUSTER_PARTITIONS.ID);

        return newId;
    }

    // -------------------------------------------------------------------------
    // Project partitions
    // -------------------------------------------------------------------------

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

        UUID newId = dsl.insertInto(PROJECT_PARTITIONS)
                .set(PROJECT_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(PROJECT_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(PROJECT_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(PROJECT_PARTITIONS.ID);

        return newId;
    }

    // -------------------------------------------------------------------------
    // Application partitions
    // -------------------------------------------------------------------------

    private UUID resolveApplicationPartition(int targetSize) {
        List<UUID> partitionIds = dsl
                .select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .orderBy(APPLICATION_PARTITIONS.PARTITION_NUMBER)
                .forUpdate()
                .fetch(APPLICATION_PARTITIONS.ID);

        for (UUID partitionId : partitionIds) {
            int count = dsl.fetchCount(APPLICATIONS, APPLICATIONS.APPLICATION_PARTITION_ID.eq(partitionId));
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

    private UUID createApplicationPartition() {
        Integer maxNum = dsl
                .select(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER))
                .from(APPLICATION_PARTITIONS)
                .fetchOne(DSL.max(APPLICATION_PARTITIONS.PARTITION_NUMBER));
        int nextNum = (maxNum != null ? maxNum : 0) + 1;

        UUID newId = dsl.insertInto(APPLICATION_PARTITIONS)
                .set(APPLICATION_PARTITIONS.PARTITION_NUMBER, nextNum)
                .set(APPLICATION_PARTITIONS.STATUS, ResourceStatus.UNKNOWN.name())
                .set(APPLICATION_PARTITIONS.GENERATION, 0L)
                .returning()
                .fetchOne()
                .get(APPLICATION_PARTITIONS.ID);

        return newId;
    }

    // -------------------------------------------------------------------------
    // ArgoCD read-side: partition list with resource counts
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // ArgoCD read-side: partition number → UUID resolution
    // -------------------------------------------------------------------------

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
     * Resolves a cluster partition UUID by its human-readable partition number.
     * Returns empty if no partition with that number exists.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findClusterPartitionIdByNumber(int partitionNumber) {
        return dsl.select(CLUSTER_PARTITIONS.ID)
                .from(CLUSTER_PARTITIONS)
                .where(CLUSTER_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(CLUSTER_PARTITIONS.ID);
    }

    /**
     * Resolves an application partition UUID by its human-readable partition number.
     * Returns empty if no partition with that number exists.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findApplicationPartitionIdByNumber(int partitionNumber) {
        return dsl.select(APPLICATION_PARTITIONS.ID)
                .from(APPLICATION_PARTITIONS)
                .where(APPLICATION_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))
                .fetchOptional(APPLICATION_PARTITIONS.ID);
    }

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
     * Empty partitions are included with {@code clusterCount = 0} (LEFT JOIN + COUNT(id)).
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
     * Returns all application partitions with the count of applications currently assigned to each.
     * Empty partitions are included with {@code applicationCount = 0} (LEFT JOIN + COUNT(id)).
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
