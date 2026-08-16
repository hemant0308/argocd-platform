package com.argocd.platform.api.repository;

import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;

@Repository
@RequiredArgsConstructor
public class ApplicationRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new application. The {@code applicationPartitionId} must be set on the entity
     * before calling this method (resolved by the service via PartitionRepository).
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ApplicationsEntity save(ApplicationsEntity entity) {
        return dsl.insertInto(APPLICATIONS)
                .set(APPLICATIONS.NAME, entity.getName())
                .set(APPLICATIONS.PROJECT_ID, entity.getProjectId())
                .set(APPLICATIONS.CLUSTER_ID, entity.getClusterId())
                .set(APPLICATIONS.APPLICATION_PARTITION_ID, entity.getApplicationPartitionId())
                .set(APPLICATIONS.STATUS, entity.getStatus())
                .set(APPLICATIONS.GENERATION, entity.getGeneration())
                .returning()
                .fetchOneInto(ApplicationsEntity.class);
    }

    /**
     * Updates name, cluster_id, and generation.
     * Partition assignment and project_id are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ApplicationsEntity update(UUID id, ApplicationsEntity entity) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.NAME, entity.getName())
                .set(APPLICATIONS.CLUSTER_ID, entity.getClusterId())
                .set(APPLICATIONS.GENERATION, entity.getGeneration())
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
}
