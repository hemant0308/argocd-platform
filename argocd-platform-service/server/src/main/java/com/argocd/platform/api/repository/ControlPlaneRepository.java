package com.argocd.platform.api.repository;

import com.argocd.platform.db.jooq.tables.pojos.ControlPlanesEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;

@Repository
@RequiredArgsConstructor
public class ControlPlaneRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new control plane record.
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ControlPlanesEntity save(ControlPlanesEntity entity) {
        return dsl.insertInto(CONTROL_PLANES)
                .set(CONTROL_PLANES.NAME, entity.getName())
                .set(CONTROL_PLANES.SERVER, entity.getServer())
                .set(CONTROL_PLANES.STATUS, entity.getStatus())
                .set(CONTROL_PLANES.CAPACITY, entity.getCapacity())
                .returning()
                .fetchOneInto(ControlPlanesEntity.class);
    }

    /**
     * Updates name, server, and capacity.
     * Does not change id or created_at.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ControlPlanesEntity update(UUID id, ControlPlanesEntity entity) {
        return dsl.update(CONTROL_PLANES)
                .set(CONTROL_PLANES.NAME, entity.getName())
                .set(CONTROL_PLANES.SERVER, entity.getServer())
                .set(CONTROL_PLANES.CAPACITY, entity.getCapacity())
                .set(CONTROL_PLANES.UPDATED_AT, DSL.currentLocalDateTime())
                .where(CONTROL_PLANES.ID.eq(id))
                .returning()
                .fetchOneInto(ControlPlanesEntity.class);
    }

    public Optional<ControlPlanesEntity> findById(UUID id) {
        return dsl.selectFrom(CONTROL_PLANES)
                .where(CONTROL_PLANES.ID.eq(id))
                .fetchOptionalInto(ControlPlanesEntity.class);
    }

    /**
     * Looks up a control plane by its unique name.
     * Used by {@link com.argocd.platform.api.assignment.ExplicitControlPlaneResolver}.
     */
    public Optional<ControlPlanesEntity> findByName(String name) {
        return dsl.selectFrom(CONTROL_PLANES)
                .where(CONTROL_PLANES.NAME.eq(name))
                .fetchOptionalInto(ControlPlanesEntity.class);
    }

    /**
     * Returns all control planes ordered by id.
     * Used by {@link com.argocd.platform.api.assignment.ConsistentHashControlPlaneResolver}
     * to build the hash ring.
     */
    public List<ControlPlanesEntity> findAll() {
        return dsl.selectFrom(CONTROL_PLANES)
                .orderBy(CONTROL_PLANES.ID)
                .fetchInto(ControlPlanesEntity.class);
    }
}
