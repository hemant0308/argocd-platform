package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.db.jooq.tables.pojos.ClustersEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.CLUSTERS;

@Repository
@RequiredArgsConstructor
public class ClusterRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new cluster. The {@code clusterPartitionId} must be set on the entity
     * before calling this method (resolved by the service via PartitionRepository).
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
     * Updates name, server, control_plane_id, and JSONB fields.
     * Partition assignment is intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ClustersEntity update(UUID id, ClustersEntity entity) {
        return dsl.update(CLUSTERS)
                .set(CLUSTERS.NAME, entity.getName())
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
     * Returns all clusters in the given partition ordered by name.
     * Unknown {@code partitionId} returns an empty list.
     */
    public List<ClusterItem> findByPartitionId(UUID partitionId) {
        return dsl.select(CLUSTERS.NAME, CLUSTERS.SERVER)
                .from(CLUSTERS)
                .where(CLUSTERS.CLUSTER_PARTITION_ID.eq(partitionId))
                .orderBy(CLUSTERS.NAME)
                .fetch(r -> ClusterItem.builder()
                        .name(r.get(CLUSTERS.NAME))
                        .server(r.get(CLUSTERS.SERVER))
                        .build());
    }
}
