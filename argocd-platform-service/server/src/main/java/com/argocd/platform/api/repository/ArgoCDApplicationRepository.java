package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.util.DeletionMode;
import com.argocd.platform.api.util.JsonbUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

@Repository
@RequiredArgsConstructor
public class ArgoCDApplicationRepository {

    private static final TypeReference<List<Map<String, Object>>> SOURCES_TYPE =
            new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    /**
     * Returns all applications visible to the plugin generator for the given partition,
     * enriched with project name, cluster name, and control-plane name.
     *
     * <h3>Deletion filtering</h3>
     * <ul>
     *   <li>{@code deleted_at IS NOT NULL} — tombstoned; always excluded.</li>
     *   <li>{@code deletion_mode = SOFT_DELETE} — app disappears from ArgoCD response
     *       immediately so ArgoCD prunes it without cascade.</li>
     *   <li>{@code deletion_mode = AWAITING_PRUNE} — finalizer synced; app disappears so
     *       ArgoCD prunes with cascade.</li>
     *   <li>{@code deletion_mode = HARD_DELETE} — included with {@code hardDelete = true}
     *       so ArgoCD syncs the {@code resources-finalizer} in this poll cycle.</li>
     *   <li>{@code deletion_mode IS NULL} — active; included normally.</li>
     * </ul>
     *
     * <h3>Join design</h3>
     * <ul>
     *   <li>INNER JOIN on {@code projects} and {@code clusters} — every application must
     *       have both. A dangling FK would indicate data corruption.</li>
     *   <li>LEFT JOIN on {@code control_planes} — a cluster is not guaranteed to have a
     *       control plane assigned; {@code controlPlane} is {@code null} in that case.</li>
     *   <li>Rows are sorted by {@code applications.name}.</li>
     * </ul>
     *
     * @param partitionId UUID of the application partition
     * @return ordered list of {@link ApplicationItem}; empty if the partition has no visible applications
     */
    @Transactional(readOnly = true)
    public List<ApplicationItem> findByPartitionId(UUID partitionId) {
        Field<String> projNameField    = PROJECTS.NAME.as("proj_name");
        Field<String> clusterNameField = CLUSTERS.NAME.as("cluster_name");
        Field<String> cpNameField      = CONTROL_PLANES.NAME.as("cp_name");

        Field<?>[] fields = new Field<?>[] {
                APPLICATIONS.ID,
                APPLICATIONS.NAME,
                projNameField,
                clusterNameField,
                cpNameField,
                APPLICATIONS.SOURCES,
                APPLICATIONS.DELETION_MODE
        };

        Result<Record> rows = dsl.select(fields)
                .from(APPLICATIONS)
                .join(PROJECTS).on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                .join(CLUSTERS).on(CLUSTERS.ID.eq(APPLICATIONS.CLUSTER_ID))
                .leftJoin(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .where(APPLICATIONS.APPLICATION_PARTITION_ID.eq(partitionId))
                // Exclude tombstoned rows
                .and(APPLICATIONS.DELETED_AT.isNull())
                // Include only: active (null) and HARD_DELETE — exclude SOFT_DELETE and AWAITING_PRUNE
                .and(APPLICATIONS.DELETION_MODE.isNull()
                        .or(APPLICATIONS.DELETION_MODE.eq(DeletionMode.HARD_DELETE.name())))
                .orderBy(APPLICATIONS.NAME.asc())
                .fetch();

        return rows.stream()
                .map(r -> ApplicationItem.builder()
                        .name(r.get(APPLICATIONS.NAME))
                        .project(r.get(projNameField))
                        .cluster(r.get(clusterNameField))
                        .controlPlane(r.get(cpNameField))
                        .sources(Objects.requireNonNullElse(
                                jsonbUtils.fromJsonb(r.get(APPLICATIONS.SOURCES), SOURCES_TYPE), List.of()))
                        .hardDelete(DeletionMode.HARD_DELETE.name().equals(r.get(APPLICATIONS.DELETION_MODE)))
                        .build())
                .toList();
    }

}
