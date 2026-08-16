package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.db.jooq.tables.pojos.ProjectsEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.argocd.platform.db.jooq.Tables.PROJECT_CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

@Repository
@RequiredArgsConstructor
public class ProjectRepository {

    private final DSLContext dsl;

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
     * Updates name and description only.
     * Partition assignment and created_by are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ProjectsEntity update(UUID id, ProjectsEntity entity) {
        return dsl.update(PROJECTS)
                .set(PROJECTS.NAME, entity.getName())
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
     * Returns all projects in the given partition ordered by name.
     * Unknown {@code partitionId} returns an empty list.
     */
    public List<ProjectItem> findByPartitionId(UUID partitionId) {
        return dsl.select(PROJECTS.ID, PROJECTS.NAME)
                .from(PROJECTS)
                .where(PROJECTS.PROJECT_PARTITION_ID.eq(partitionId))
                .orderBy(PROJECTS.NAME)
                .fetch(r -> ProjectItem.builder()
                        .id(r.get(PROJECTS.ID))
                        .name(r.get(PROJECTS.NAME))
                        .build());
    }
}
