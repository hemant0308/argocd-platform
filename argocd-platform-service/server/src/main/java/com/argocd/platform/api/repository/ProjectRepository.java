package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ProjectClusterItem;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ProjectsEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECT_CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECT_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

@Repository
@RequiredArgsConstructor
public class ProjectRepository {

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    /**
     * Inserts a new project. The {@code projectPartitionId} must be set on the entity
     * before calling this method (resolved by the service via PartitionRepository).
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ProjectsEntity save(ProjectsEntity entity) {
        return dsl.insertInto(PROJECTS)
                .set(PROJECTS.NAME, entity.getName())
                .set(PROJECTS.DESCRIPTION, entity.getDescription())
                .set(PROJECTS.STATUS, entity.getStatus())
                .set(PROJECTS.CREATED_BY, entity.getCreatedBy())
                .set(PROJECTS.PROJECT_PARTITION_ID, entity.getProjectPartitionId())
                .returning()
                .fetchOneInto(ProjectsEntity.class);
    }

    /**
     * Updates description only.
     * Name, partition assignment, and created_by are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ProjectsEntity update(UUID id, ProjectsEntity entity) {
        return dsl.update(PROJECTS)
                .set(PROJECTS.DESCRIPTION, entity.getDescription())
                .set(PROJECTS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(PROJECTS.ID.eq(id))
                .returning()
                .fetchOneInto(ProjectsEntity.class);
    }

    public Optional<ProjectsEntity> findById(UUID id) {
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.ID.eq(id))
                .fetchOptionalInto(ProjectsEntity.class);
    }

    public Optional<ProjectsEntity> findByName(String name) {
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.NAME.eq(name))
                .fetchOptionalInto(ProjectsEntity.class);
    }

    /**
     * Batch-inserts rows into {@code project_clusters} for the given cluster IDs.
     * Status defaults to {@link ResourceStatus#UNKNOWN}.
     */
    public void saveProjectClusters(UUID projectId, List<UUID> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return;
        }
        dsl.batch(
                clusterIds.stream()
                        .map(clusterId -> dsl.insertInto(PROJECT_CLUSTERS)
                                .set(PROJECT_CLUSTERS.PROJECT_ID, projectId)
                                .set(PROJECT_CLUSTERS.CLUSTER_ID, clusterId)
                                .set(PROJECT_CLUSTERS.STATUS, ResourceStatus.UNKNOWN.name()))
                        .collect(Collectors.toList())
        ).execute();
    }

    /**
     * Removes all cluster associations for a project.
     * Called before re-inserting the updated cluster list on PUT.
     */
    public void deleteProjectClusters(UUID projectId) {
        dsl.deleteFrom(PROJECT_CLUSTERS)
                .where(PROJECT_CLUSTERS.PROJECT_ID.eq(projectId))
                .execute();
    }

    /**
     * Returns {@code true} if the given cluster is associated with the given project
     * via the {@code project_clusters} join table.
     *
     * <p>Used by {@code ApplicationService} to validate that an application's target
     * cluster belongs to the application's project before persisting the record.
     */
    public boolean isClusterInProject(UUID projectId, UUID clusterId) {
        return dsl.fetchExists(
                PROJECT_CLUSTERS,
                PROJECT_CLUSTERS.PROJECT_ID.eq(projectId)
                        .and(PROJECT_CLUSTERS.CLUSTER_ID.eq(clusterId)));
    }

    /**
     * Returns all projects ordered by name.
     * Used by the management UI list endpoint.
     */
    public List<ProjectsEntity> findAll() {
        return dsl.selectFrom(PROJECTS)
                .orderBy(PROJECTS.NAME)
                .fetchInto(ProjectsEntity.class);
    }

    /**
     * Returns the cluster associations for a set of project IDs in a single JOIN query.
     * Result: projectId → list of {@link ProjectClusterItem} (name + namespaces).
     * Projects with no clusters are absent from the map (callers should use {@code getOrDefault}).
     */
    public Map<UUID, List<ProjectClusterItem>> findClustersForProjects(List<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        record ClusterRow(UUID projectId, String clusterName, JSONB namespaces) {}

        return dsl.select(PROJECT_CLUSTERS.PROJECT_ID, CLUSTERS.NAME, CLUSTERS.NAMESPACES)
                .from(PROJECT_CLUSTERS)
                .join(CLUSTERS).on(CLUSTERS.ID.eq(PROJECT_CLUSTERS.CLUSTER_ID))
                .where(PROJECT_CLUSTERS.PROJECT_ID.in(projectIds))
                .fetch(r -> new ClusterRow(
                        r.get(PROJECT_CLUSTERS.PROJECT_ID),
                        r.get(CLUSTERS.NAME),
                        r.get(CLUSTERS.NAMESPACES)))
                .stream()
                .collect(Collectors.groupingBy(
                        ClusterRow::projectId,
                        Collectors.mapping(
                                row -> ProjectClusterItem.builder()
                                        .name(row.clusterName())
                                        .namespaces(Objects.requireNonNullElse(
                                                jsonbUtils.fromJsonb(row.namespaces(), STRING_LIST), List.of()))
                                        .build(),
                                Collectors.toList())));
    }

    /**
     * Updates the status of all projects in the given partition number.
     * Resolves the partition UUID via subquery — no pre-lookup required by the caller.
     * Uses last-write-wins semantics — whichever control plane reports last sets the status.
     *
     * <p>This method intentionally does NOT publish any {@code PartitionChangedEvent}
     * to avoid triggering a reconcile loop.
     *
     * @return the number of rows updated (0 if the partition does not exist)
     */
    public int updateStatusByPartitionNumber(int partitionNumber, String status) {
        return dsl.update(PROJECTS)
                .set(PROJECTS.STATUS, status)
                .set(PROJECTS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(PROJECTS.PROJECT_PARTITION_ID.in(
                        DSL.select(PROJECT_PARTITIONS.ID)
                                .from(PROJECT_PARTITIONS)
                                .where(PROJECT_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))))
                .execute();
    }

    /**
     * Deletes a project by id.
     * The DB CASCADE drops associated {@code project_clusters} rows automatically.
     * Associated {@code applications} rows are also CASCADE-deleted — the caller should
     * warn the user before invoking this.
     */
    public void deleteById(UUID id) {
        dsl.deleteFrom(PROJECTS)
                .where(PROJECTS.ID.eq(id))
                .execute();
    }

    /**
     * Returns all projects in the given partition ordered by name, each carrying
     * the list of clusters assigned to it (via {@code project_clusters}).
     *
     * <p>Uses two queries to avoid N+1:
     * <ol>
     *   <li>Fetch all projects in the partition.</li>
     *   <li>Fetch all cluster associations for those projects in one JOIN query.</li>
     * </ol>
     * The cluster lists are then merged into each {@link ProjectItem} in Java.
     *
     * <p>Unknown {@code partitionId} returns an empty list.
     */
    public List<ProjectItem> findByPartitionId(UUID partitionId) {
        // Query 1: all projects in this partition
        List<ProjectsEntity> projects = dsl.selectFrom(PROJECTS)
                .where(PROJECTS.PROJECT_PARTITION_ID.eq(partitionId))
                .orderBy(PROJECTS.NAME)
                .fetchInto(ProjectsEntity.class);

        if (projects.isEmpty()) {
            return List.of();
        }

        List<UUID> projectIds = projects.stream()
                .map(ProjectsEntity::getId)
                .collect(Collectors.toList());

        // Query 2: cluster name + namespaces for every project in one shot
        record ClusterRow(UUID projectId, String clusterName, JSONB namespaces) {}

        List<ClusterRow> clusterRows = dsl
                .select(PROJECT_CLUSTERS.PROJECT_ID, CLUSTERS.NAME, CLUSTERS.NAMESPACES)
                .from(PROJECT_CLUSTERS)
                .join(CLUSTERS).on(CLUSTERS.ID.eq(PROJECT_CLUSTERS.CLUSTER_ID))
                .where(PROJECT_CLUSTERS.PROJECT_ID.in(projectIds))
                .fetch(r -> new ClusterRow(
                        r.get(PROJECT_CLUSTERS.PROJECT_ID),
                        r.get(CLUSTERS.NAME),
                        r.get(CLUSTERS.NAMESPACES)));

        // Group by project ID
        Map<UUID, List<ProjectClusterItem>> clustersByProject = clusterRows.stream()
                .collect(Collectors.groupingBy(
                        ClusterRow::projectId,
                        Collectors.mapping(
                                row -> ProjectClusterItem.builder()
                                        .name(row.clusterName())
                                        .namespaces(Objects.requireNonNullElse(
                                                jsonbUtils.fromJsonb(row.namespaces(), STRING_LIST), List.of()))
                                        .build(),
                                Collectors.toList())));

        return projects.stream()
                .map(p -> ProjectItem.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .clusters(clustersByProject.getOrDefault(p.getId(), List.of()))
                        .build())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
}
