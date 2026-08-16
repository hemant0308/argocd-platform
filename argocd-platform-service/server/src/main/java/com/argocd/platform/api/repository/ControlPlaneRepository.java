package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.ControlPlaneResponse;
import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;

/**
 * Repository for {@code control_planes}.
 *
 * <p>All queries use an explicit column list (never {@code selectFrom(CONTROL_PLANES)})
 * to keep SELECT lists stable when the schema evolves.
 */
@Repository
@RequiredArgsConstructor
public class ControlPlaneRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new control plane and returns the persisted data as a {@link ControlPlaneResponse}.
     *
     * @return the saved response with id, timestamps, and endpoint populated from the DB
     */
    public ControlPlaneResponse save(ControlPlanesEntity entity, String endpoint) {
        Record r = dsl.insertInto(CONTROL_PLANES)
                .set(CONTROL_PLANES.NAME, entity.getName())
                .set(CONTROL_PLANES.SERVER, entity.getServer())
                .set(CONTROL_PLANES.STATUS, entity.getStatus())
                .set(CONTROL_PLANES.ENDPOINT, endpoint)
                .returning(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS,
                        CONTROL_PLANES.ENDPOINT,
                        CONTROL_PLANES.CREATED_AT,
                        CONTROL_PLANES.UPDATED_AT)
                .fetchOne();
        return toResponse(r);
    }

    /**
     * Updates name, server, and endpoint.
     *
     * @return the updated response with refreshed updated_at
     */
    public ControlPlaneResponse update(UUID id, ControlPlanesEntity entity, String endpoint) {
        Record r = dsl.update(CONTROL_PLANES)
                .set(CONTROL_PLANES.NAME, entity.getName())
                .set(CONTROL_PLANES.SERVER, entity.getServer())
                .set(CONTROL_PLANES.ENDPOINT, endpoint)
                .set(CONTROL_PLANES.UPDATED_AT, DSL.currentLocalDateTime())
                .where(CONTROL_PLANES.ID.eq(id))
                .returning(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS,
                        CONTROL_PLANES.ENDPOINT,
                        CONTROL_PLANES.CREATED_AT,
                        CONTROL_PLANES.UPDATED_AT)
                .fetchOne();
        return toResponse(r);
    }

    /**
     * Looks up a control plane by id.
     * Returns a lightweight entity (id, name, server, status only — no endpoint).
     * Used by {@link com.argocd.platform.api.service.ClusterService} to resolve the CP name.
     */
    public Optional<ControlPlanesEntity> findById(UUID id) {
        return dsl.select(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS)
                .from(CONTROL_PLANES)
                .where(CONTROL_PLANES.ID.eq(id))
                .fetchOptional(r -> toEntity(r));
    }

    /**
     * Looks up a control plane by its unique name.
     * Used by {@link com.argocd.platform.api.assignment.ExplicitControlPlaneResolver}.
     */
    public Optional<ControlPlanesEntity> findByName(String name) {
        return dsl.select(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS)
                .from(CONTROL_PLANES)
                .where(CONTROL_PLANES.NAME.eq(name))
                .fetchOptional(r -> toEntity(r));
    }

    /**
     * Returns all control planes ordered by id.
     * Used by {@link com.argocd.platform.api.assignment.ConsistentHashControlPlaneResolver}
     * to build the hash ring. Only {@code id} is required by the resolver.
     */
    public List<ControlPlanesEntity> findAll() {
        return dsl.select(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS)
                .from(CONTROL_PLANES)
                .orderBy(CONTROL_PLANES.ID)
                .fetch(r -> toEntity(r));
    }

    /**
     * Returns all control planes with their endpoint, ordered by id.
     * Used by the management UI list and cluster enrichment queries.
     */
    public List<ControlPlaneResponse> findAllWithEndpoint() {
        return dsl.select(
                        CONTROL_PLANES.ID,
                        CONTROL_PLANES.NAME,
                        CONTROL_PLANES.SERVER,
                        CONTROL_PLANES.STATUS,
                        CONTROL_PLANES.ENDPOINT,
                        CONTROL_PLANES.CREATED_AT,
                        CONTROL_PLANES.UPDATED_AT)
                .from(CONTROL_PLANES)
                .orderBy(CONTROL_PLANES.ID)
                .fetch(r -> toResponse(r));
    }

    /**
     * Deletes a control plane by id.
     * FK violations (clusters still referencing this CP) propagate as
     * {@link org.springframework.dao.DataIntegrityViolationException} → 409.
     */
    public void deleteById(UUID id) {
        dsl.deleteFrom(CONTROL_PLANES)
                .where(CONTROL_PLANES.ID.eq(id))
                .execute();
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private ControlPlanesEntity toEntity(Record r) {
        return new ControlPlanesEntity()
                .setId(r.get(CONTROL_PLANES.ID))
                .setName(r.get(CONTROL_PLANES.NAME))
                .setServer(r.get(CONTROL_PLANES.SERVER))
                .setStatus(r.get(CONTROL_PLANES.STATUS));
    }

    private ControlPlaneResponse toResponse(Record r) {
        return ControlPlaneResponse.builder()
                .id(r.get(CONTROL_PLANES.ID))
                .name(r.get(CONTROL_PLANES.NAME))
                .server(r.get(CONTROL_PLANES.SERVER))
                .status(r.get(CONTROL_PLANES.STATUS))
                .endpoint(r.get(CONTROL_PLANES.ENDPOINT))
                .createdAt(r.get(CONTROL_PLANES.CREATED_AT, LocalDateTime.class))
                .updatedAt(r.get(CONTROL_PLANES.UPDATED_AT, LocalDateTime.class))
                .build();
    }
}
