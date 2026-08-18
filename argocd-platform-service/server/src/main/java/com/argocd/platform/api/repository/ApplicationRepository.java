package com.argocd.platform.api.repository;

import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;

@Repository
@RequiredArgsConstructor
public class ApplicationRepository {

    // sources JSONB column — will be a typed constant (APPLICATIONS.SOURCES) after jOOQ regen.
    // Using DSL.field until then to avoid a compilation dependency on the regenerated class.
    static final Field<JSONB> SOURCES = DSL.field(DSL.name("sources"), JSONB.class);

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

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
                .set(SOURCES, jsonbUtils.toJsonb(sources))
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
                .set(SOURCES, jsonbUtils.toJsonb(sources))
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
     * Returns all applications ordered by name.
     * Used by the management UI list endpoint.
     * NOTE: does not include the {@code sources} JSONB — call {@link #findAllSourcesMap()} separately.
     */
    public List<ApplicationsEntity> findAll() {
        return dsl.selectFrom(APPLICATIONS)
                .orderBy(APPLICATIONS.NAME)
                .fetchInto(ApplicationsEntity.class);
    }

    /**
     * Returns a map of application id → raw sources JSONB for all applications.
     * Used together with {@link #findAll()} to avoid an N+1 query when building responses.
     */
    public Map<UUID, JSONB> findAllSourcesMap() {
        return dsl.select(APPLICATIONS.ID, SOURCES)
                .from(APPLICATIONS)
                .fetch()
                .stream()
                .filter(r -> r.get(SOURCES) != null)
                .collect(java.util.stream.Collectors.toMap(
                        r -> r.get(APPLICATIONS.ID),
                        r -> r.get(SOURCES)));
    }

    /**
     * Deletes an application by id.
     * Callers must verify existence before calling this method.
     */
    public void deleteById(UUID id) {
        dsl.deleteFrom(APPLICATIONS)
                .where(APPLICATIONS.ID.eq(id))
                .execute();
    }

}
