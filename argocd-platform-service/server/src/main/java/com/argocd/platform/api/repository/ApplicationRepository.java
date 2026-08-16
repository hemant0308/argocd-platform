package com.argocd.platform.api.repository;

import com.argocd.platform.db.jooq.tables.pojos.ApplicationsEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Repository
@RequiredArgsConstructor
public class ApplicationRepository {

    // sources JSONB column — will be a typed constant (APPLICATIONS.SOURCES) after jOOQ regen.
    // Using DSL.field until then to avoid a compilation dependency on the regenerated class.
    static final Field<JSONB> SOURCES = DSL.field(DSL.name("sources"), JSONB.class);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

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
                .set(SOURCES, toJsonb(sources))
                .returning()
                .fetchOneInto(ApplicationsEntity.class);
    }

    /**
     * Updates name, cluster_id, generation, and sources.
     * Partition assignment and project_id are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ApplicationsEntity update(UUID id, ApplicationsEntity entity, List<Map<String, Object>> sources) {
        return dsl.update(APPLICATIONS)
                .set(APPLICATIONS.NAME, entity.getName())
                .set(APPLICATIONS.CLUSTER_ID, entity.getClusterId())
                .set(APPLICATIONS.GENERATION, entity.getGeneration())
                .set(SOURCES, toJsonb(sources))
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JSONB toJsonb(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSONB.jsonb(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize sources to JSONB: " + e.getMessage(), e);
        }
    }

    public <T> T fromJsonb(JSONB jsonb, TypeReference<T> typeRef) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonb.data(), typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSONB '{}': {}", jsonb.data(), e.getMessage());
            return null;
        }
    }
}
