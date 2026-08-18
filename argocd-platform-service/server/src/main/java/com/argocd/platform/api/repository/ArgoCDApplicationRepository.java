package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.util.JsonbUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
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

    // sources JSONB column — typed constant after jOOQ regen; raw field until then.
    private static final Field<JSONB> SOURCES = DSL.field(DSL.name("applications", "sources"), JSONB.class);

    private static final TypeReference<List<Map<String, Object>>> SOURCES_TYPE =
            new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    /**
     * Returns all applications in the given partition enriched with project name,
     * cluster name, and control-plane name. Sources are returned verbatim from the
     * {@code sources} JSONB column — no fixed schema is imposed.
     *
     * <p>Design notes:
     * <ul>
     *   <li>INNER JOIN on {@code projects} and {@code clusters} — every application must
     *       have both. A dangling FK would indicate data corruption.</li>
     *   <li>LEFT JOIN on {@code control_planes} — a cluster is not guaranteed to have a
     *       control plane assigned; {@code controlPlane} is {@code null} in that case.</li>
     *   <li>Rows are sorted by {@code applications.name}.</li>
     * </ul>
     *
     * @param partitionId UUID of the application partition
     * @return ordered list of {@link ApplicationItem}; empty if the partition has no applications
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
                SOURCES
        };

        Result<Record> rows = dsl.select(fields)
                .from(APPLICATIONS)
                .join(PROJECTS).on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                .join(CLUSTERS).on(CLUSTERS.ID.eq(APPLICATIONS.CLUSTER_ID))
                .leftJoin(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .where(APPLICATIONS.APPLICATION_PARTITION_ID.eq(partitionId))
                .orderBy(APPLICATIONS.NAME.asc())
                .fetch();

        return rows.stream()
                .map(r -> ApplicationItem.builder()
                        .name(r.get(APPLICATIONS.NAME))
                        .project(r.get(projNameField))
                        .cluster(r.get(clusterNameField))
                        .controlPlane(r.get(cpNameField))
                        .sources(Objects.requireNonNullElse(
                                jsonbUtils.fromJsonb(r.get(SOURCES), SOURCES_TYPE), List.of()))
                        .build())
                .toList();
    }

}
