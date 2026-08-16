package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ApplicationSourceItem;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_SOURCES;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

@Repository
@RequiredArgsConstructor
public class ArgoCDApplicationRepository {

    private final DSLContext dsl;

    /**
     * Returns all applications in the given partition enriched with their project name,
     * cluster name, control-plane name, and ordered sources.
     *
     * <p>Design notes:
     * <ul>
     *   <li>INNER JOIN on {@code projects} and {@code clusters} — every application must
     *       have both. A dangling FK would indicate data corruption.</li>
     *   <li>LEFT JOIN on {@code control_planes} — a cluster is not guaranteed to have a
     *       control plane assigned; {@code controlPlane} is {@code null} in that case.</li>
     *   <li>LEFT JOIN on {@code application_sources} — an application may have zero sources;
     *       such rows are filtered out in the Java grouping step, producing an empty list.</li>
     *   <li>Rows are sorted by {@code applications.name}, then {@code source_order NULLS LAST}.
     *       A {@link LinkedHashMap} preserves this order when grouping by application id.</li>
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

        // Wrap in Field<?>[] to force the varargs select(Field<?>...) overload.
        // Passing 10 typed fields individually would resolve to the typed
        // select(Field<T1>,...,Field<T10>) overload returning Result<Record10<...>>,
        // which is incompatible with Result<Record>.
        Field<?>[] fields = new Field<?>[] {
                APPLICATIONS.ID,
                APPLICATIONS.NAME,
                projNameField,
                clusterNameField,
                cpNameField,
                APPLICATION_SOURCES.REPO_URL,
                APPLICATION_SOURCES.REVISION,
                APPLICATION_SOURCES.PATH,
                APPLICATION_SOURCES.CHART,
                APPLICATION_SOURCES.SOURCE_ORDER
        };

        Result<Record> rows = dsl.select(fields)
                .from(APPLICATIONS)
                .join(PROJECTS).on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                .join(CLUSTERS).on(CLUSTERS.ID.eq(APPLICATIONS.CLUSTER_ID))
                .leftJoin(CONTROL_PLANES).on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                .leftJoin(APPLICATION_SOURCES).on(APPLICATION_SOURCES.APPLICATION_ID.eq(APPLICATIONS.ID))
                .where(APPLICATIONS.APPLICATION_PARTITION_ID.eq(partitionId))
                .orderBy(APPLICATIONS.NAME.asc(), APPLICATION_SOURCES.SOURCE_ORDER.asc().nullsLast())
                .fetch();

        // Group rows by application ID, preserving the sort order established by the DB query.
        Map<UUID, List<Record>> grouped = new LinkedHashMap<>();
        for (Record r : rows) {
            grouped.computeIfAbsent(r.get(APPLICATIONS.ID), k -> new ArrayList<>()).add(r);
        }

        return grouped.values().stream()
                .map(group -> {
                    Record first = group.get(0);

                    // Filter out the null source row produced by LEFT JOIN when an app has no sources.
                    List<ApplicationSourceItem> sources = group.stream()
                            .filter(r -> r.get(APPLICATION_SOURCES.REPO_URL) != null)
                            .map(r -> ApplicationSourceItem.builder()
                                    .repoUrl(r.get(APPLICATION_SOURCES.REPO_URL))
                                    .revision(r.get(APPLICATION_SOURCES.REVISION))
                                    .path(r.get(APPLICATION_SOURCES.PATH))
                                    .chart(r.get(APPLICATION_SOURCES.CHART))
                                    .build())
                            .toList();

                    return ApplicationItem.builder()
                            .name(first.get(APPLICATIONS.NAME))
                            .project(first.get(projNameField))
                            .cluster(first.get(clusterNameField))
                            .controlPlane(first.get(cpNameField))
                            .sources(sources)
                            .build();
                })
                .toList();
    }
}
