package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.CLUSTER_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;

@Repository
public class ClusterRepository {

    private static final TypeReference<LinkedHashMap<String, Object>> AUTH_TYPE =
            new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    public ClusterRepository(DSLContext dsl, JsonbUtils jsonbUtils) {
        this.dsl = dsl;
        this.jsonbUtils = jsonbUtils;
    }

    /**
     * Inserts a new cluster.
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ClustersEntity save(ClustersEntity entity) {
        return dsl.insertInto(CLUSTERS)
                .set(CLUSTERS.NAME, entity.getName())
                .set(CLUSTERS.SERVER, entity.getServer())
                .set(CLUSTERS.STATUS, entity.getStatus())
                .set(CLUSTERS.CONTROL_PLANE_ID, entity.getControlPlaneId())
                .set(CLUSTERS.CLUSTER_PARTITION_ID, entity.getClusterPartitionId())
                .set(CLUSTERS.NAMESPACES, entity.getNamespaces())
                .set(CLUSTERS.LABELS, entity.getLabels())
                .set(CLUSTERS.AUTH, entity.getAuth())
                .returning()
                .fetchOneInto(ClustersEntity.class);
    }

    /**
     * Updates server, control_plane_id, and JSONB fields.
     * Name and partition assignment are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ClustersEntity update(UUID id, ClustersEntity entity) {
        return dsl.update(CLUSTERS)
                .set(CLUSTERS.SERVER, entity.getServer())
                .set(CLUSTERS.CONTROL_PLANE_ID, entity.getControlPlaneId())
                .set(CLUSTERS.NAMESPACES, entity.getNamespaces())
                .set(CLUSTERS.LABELS, entity.getLabels())
                .set(CLUSTERS.AUTH, entity.getAuth())
                .set(CLUSTERS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(CLUSTERS.ID.eq(id))
                .returning()
                .fetchOneInto(ClustersEntity.class);
    }

    public Optional<ClustersEntity> findById(UUID id) {
        return dsl.selectFrom(CLUSTERS)
                .where(CLUSTERS.ID.eq(id))
                .fetchOptionalInto(ClustersEntity.class);
    }

    public Optional<ClustersEntity> findByName(String name) {
        return dsl.selectFrom(CLUSTERS)
                .where(CLUSTERS.NAME.eq(name))
                .fetchOptionalInto(ClustersEntity.class);
    }

    /**
     * Returns all clusters ordered by name.
     * Used by the management UI list endpoint.
     */
    public List<ClustersEntity> findAll() {
        return dsl.selectFrom(CLUSTERS)
                .orderBy(CLUSTERS.NAME)
                .fetchInto(ClustersEntity.class);
    }

    /**
     * Deletes the cluster with the given id.
     * Callers must verify existence before calling this method.
     * FK violations (cluster referenced by applications) propagate as
     * {@link org.springframework.dao.DataIntegrityViolationException} and are mapped to 409
     * by {@link com.argocd.platform.api.exception.GlobalExceptionHandler}.
     */
    public void deleteById(UUID id) {
        dsl.deleteFrom(CLUSTERS)
                .where(CLUSTERS.ID.eq(id))
                .execute();
    }

    /**
     * Updates the status of all clusters in the given partition that are assigned to
     * the named control plane. Resolves both the partition and the control plane via
     * subqueries — no pre-lookup of UUIDs required by the caller.
     *
     * <p>This method intentionally does NOT publish any {@code PartitionChangedEvent}
     * to avoid triggering a reconcile loop (sync → notify → status update → notify → …).
     *
     * @return the number of rows updated (0 if the partition or CP does not exist)
     */
    public int updateStatusByPartitionNumberAndControlPlaneName(
            int partitionNumber, String controlPlaneName, String status) {
        return dsl.update(CLUSTERS)
                .set(CLUSTERS.STATUS, status)
                .set(CLUSTERS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(CLUSTERS.CLUSTER_PARTITION_ID.in(
                        DSL.select(CLUSTER_PARTITIONS.ID)
                                .from(CLUSTER_PARTITIONS)
                                .where(CLUSTER_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))))
                .and(CLUSTERS.CONTROL_PLANE_ID.in(
                        DSL.select(CONTROL_PLANES.ID)
                                .from(CONTROL_PLANES)
                                .where(CONTROL_PLANES.NAME.eq(controlPlaneName))))
                .execute();
    }

    /**
     * Returns all clusters in the given partition ordered by name, enriched with their
     * control-plane name. The auth JSONB is deserialised as-is into {@code config}
     * and passed verbatim to the cluster-registration Helm chart.
     */
    public List<ClusterItem> findByPartitionId(UUID partitionId) {
        Field<String> cpNameField = CONTROL_PLANES.NAME.as("cp_name");

        return dsl.select(CLUSTERS.NAME, CLUSTERS.SERVER, CLUSTERS.AUTH, cpNameField)
                .from(CLUSTERS)
                .leftJoin(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .where(CLUSTERS.CLUSTER_PARTITION_ID.eq(partitionId))
                .orderBy(CLUSTERS.NAME)
                .fetch(r -> ClusterItem.builder()
                        .name(r.get(CLUSTERS.NAME))
                        .server(r.get(CLUSTERS.SERVER))
                        .controlPlane(r.get(cpNameField))
                        .config(jsonbUtils.fromJsonb(r.get(CLUSTERS.AUTH), AUTH_TYPE))
                        .build());
    }

}
