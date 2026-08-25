package com.argocd.platform.api.repository;

import com.argocd.platform.api.model.response.argocd.ApplicationSetItem;
import com.argocd.platform.api.util.JsonbUtils;
import com.argocd.platform.db.jooq.tables.pojos.ApplicationsetsEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.argocd.platform.db.jooq.Tables.APPLICATIONSET_PARTITIONS;
import static com.argocd.platform.db.jooq.Tables.APPLICATIONSETS;
import static com.argocd.platform.db.jooq.Tables.CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.CONTROL_PLANES;
import static com.argocd.platform.db.jooq.Tables.PROJECT_CLUSTERS;
import static com.argocd.platform.db.jooq.Tables.PROJECTS;

/**
 * CRUD and ArgoCD plugin-side queries for user-defined ApplicationSets.
 *
 * <p><b>Architectural rule (permanent):</b> Control planes are stateless. The CP-to-applicationset
 * mapping is derived at query time via:
 * {@code applicationsets → projects → project_clusters → clusters → control_planes}.
 * No control_plane_id is stored on either {@code applicationsets} or
 * {@code applicationset_partitions}.
 */
@Repository
@RequiredArgsConstructor
public class ApplicationSetRepository {

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonbUtils jsonbUtils;

    // =========================================================================
    // Write path
    // =========================================================================

    /**
     * Inserts a new ApplicationSet. The {@code applicationsetPartitionId} must be set
     * on the entity before calling (resolved by the service via PartitionRepository).
     *
     * @return the saved entity with id and timestamps populated from the DB
     */
    public ApplicationsetsEntity save(ApplicationsetsEntity entity,
                                      List<Map<String, Object>> generators,
                                      Map<String, Object> template) {
        return dsl.insertInto(APPLICATIONSETS)
                .set(APPLICATIONSETS.NAME, entity.getName())
                .set(APPLICATIONSETS.PROJECT_ID, entity.getProjectId())
                .set(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID,
                        entity.getApplicationsetPartitionId())
                .set(APPLICATIONSETS.GENERATOR_SPEC, jsonbUtils.toJsonb(generators))
                .set(APPLICATIONSETS.TEMPLATE_SPEC, jsonbUtils.toJsonb(template))
                .set(APPLICATIONSETS.GO_TEMPLATE, entity.getGoTemplate())
                .set(APPLICATIONSETS.STATUS, entity.getStatus())
                .returning()
                .fetchOneInto(ApplicationsetsEntity.class);
    }

    /**
     * Updates generator_spec, template_spec, and go_template.
     * Name, partition assignment, and project_id are intentionally excluded from updates.
     *
     * @return the updated entity with refreshed updated_at
     */
    public ApplicationsetsEntity update(UUID id,
                                        List<Map<String, Object>> generators,
                                        Map<String, Object> template,
                                        boolean goTemplate) {
        return dsl.update(APPLICATIONSETS)
                .set(APPLICATIONSETS.GENERATOR_SPEC, jsonbUtils.toJsonb(generators))
                .set(APPLICATIONSETS.TEMPLATE_SPEC, jsonbUtils.toJsonb(template))
                .set(APPLICATIONSETS.GO_TEMPLATE, goTemplate)
                .set(APPLICATIONSETS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONSETS.ID.eq(id))
                .returning()
                .fetchOneInto(ApplicationsetsEntity.class);
    }

    public Optional<ApplicationsetsEntity> findById(UUID id) {
        return dsl.selectFrom(APPLICATIONSETS)
                .where(APPLICATIONSETS.ID.eq(id))
                .fetchOptionalInto(ApplicationsetsEntity.class);
    }

    /**
     * Returns all ApplicationSets ordered by name.
     * Does not include generator_spec / template_spec JSONB — call
     * {@link #findGeneratorSpecById} and {@link #findTemplateSpecById} to load them separately.
     */
    public List<ApplicationsetsEntity> findAll() {
        return dsl.selectFrom(APPLICATIONSETS)
                .orderBy(APPLICATIONSETS.NAME)
                .fetchInto(ApplicationsetsEntity.class);
    }

    public JSONB findGeneratorSpecById(UUID id) {
        return dsl.select(APPLICATIONSETS.GENERATOR_SPEC)
                .from(APPLICATIONSETS)
                .where(APPLICATIONSETS.ID.eq(id))
                .fetchOne(APPLICATIONSETS.GENERATOR_SPEC);
    }

    public JSONB findTemplateSpecById(UUID id) {
        return dsl.select(APPLICATIONSETS.TEMPLATE_SPEC)
                .from(APPLICATIONSETS)
                .where(APPLICATIONSETS.ID.eq(id))
                .fetchOne(APPLICATIONSETS.TEMPLATE_SPEC);
    }

    /**
     * Returns the distinct set of applicationset_partition IDs for all ApplicationSets
     * that belong to the given project. Used by {@link com.argocd.platform.api.service.ProjectService}
     * when project cluster associations change — bumping these partition generations ensures
     * the ArgoCD plugin cache is evicted so the CP fan-out re-renders on the next poll.
     */
    public Set<UUID> findDistinctPartitionIdsByProjectId(UUID projectId) {
        return dsl.selectDistinct(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID)
                .from(APPLICATIONSETS)
                .where(APPLICATIONSETS.PROJECT_ID.eq(projectId))
                .fetchSet(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID);
    }

    /**
     * Hard deletes an ApplicationSet by id. The caller is responsible for publishing
     * a {@link com.argocd.platform.api.cache.event.PartitionChangedEvent} so the
     * plugin cache is invalidated and ArgoCD prunes the ApplicationSet on its next poll.
     */
    public void deleteById(UUID id) {
        dsl.deleteFrom(APPLICATIONSETS)
                .where(APPLICATIONSETS.ID.eq(id))
                .execute();
    }

    // =========================================================================
    // Status update — called by ArgoCDStatusService on applicationset-partition sync
    // =========================================================================

    /**
     * Sets {@code status} for all ApplicationSets in the given partition whose project
     * has at least one cluster on the named control plane.
     *
     * <h3>CP-scoped join</h3>
     * {@code applicationsets → projects → project_clusters → clusters → control_planes}
     * ensures that only ApplicationSets whose project is deployed to {@code controlPlaneName}
     * are updated — correct even during a cluster failover.
     *
     * @param partitionNumber  the applicationset partition number from the notification
     * @param controlPlaneName the control-plane name from the notification
     * @param status           the status to set (typically {@code "ACTIVE"})
     * @return rows updated
     */
    @Transactional
    public int updateStatusByPartitionNumberAndControlPlaneName(
            int partitionNumber, String controlPlaneName, String status) {
        return dsl.update(APPLICATIONSETS)
                .set(APPLICATIONSETS.STATUS, status)
                .set(APPLICATIONSETS.UPDATED_AT, DSL.currentLocalDateTime())
                .where(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID.in(
                        DSL.select(APPLICATIONSET_PARTITIONS.ID)
                                .from(APPLICATIONSET_PARTITIONS)
                                .where(APPLICATIONSET_PARTITIONS.PARTITION_NUMBER.eq(partitionNumber))))
                .and(APPLICATIONSETS.PROJECT_ID.in(
                        DSL.select(PROJECT_CLUSTERS.PROJECT_ID)
                                .from(PROJECT_CLUSTERS)
                                .join(CLUSTERS).on(CLUSTERS.ID.eq(PROJECT_CLUSTERS.CLUSTER_ID))
                                .join(CONTROL_PLANES)
                                        .on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                                .where(CONTROL_PLANES.NAME.eq(controlPlaneName))))
                .execute();
    }

    // =========================================================================
    // ArgoCD plugin read path
    // =========================================================================

    /**
     * Returns all ApplicationSets in the given partition whose project has at least one
     * cluster on the named control plane.
     *
     * <p>Fan-out join path:
     * {@code applicationsets.project_id → project_clusters.project_id →
     * clusters.control_plane_id → control_planes.name = cpName}
     *
     * <p>Used by {@link com.argocd.platform.api.service.argocd.ArgoCDPluginService}
     * to build the {@code applicationset-groups} plugin response entry for each CP.
     *
     * @param partitionId the UUID of the applicationset partition
     * @param cpName      the control-plane name to filter by
     * @return list of ApplicationSetItems for this partition+CP; empty if none
     */
    @Transactional(readOnly = true)
    public List<ApplicationSetItem> findByPartitionIdAndControlPlaneName(
            UUID partitionId, String cpName) {
        // Declare aliased field as variable so it can be safely referenced in fetch().
        org.jooq.Field<String> projectNameField = PROJECTS.NAME.as("project_name");

        return dsl.select(
                        APPLICATIONSETS.ID,
                        APPLICATIONSETS.NAME,
                        projectNameField,
                        APPLICATIONSETS.GENERATOR_SPEC,
                        APPLICATIONSETS.TEMPLATE_SPEC,
                        APPLICATIONSETS.GO_TEMPLATE)
                .from(APPLICATIONSETS)
                .join(PROJECTS).on(PROJECTS.ID.eq(APPLICATIONSETS.PROJECT_ID))
                .where(APPLICATIONSETS.APPLICATIONSET_PARTITION_ID.eq(partitionId))
                .and(DSL.exists(
                        DSL.selectOne()
                                .from(PROJECT_CLUSTERS)
                                .join(CLUSTERS).on(CLUSTERS.ID.eq(PROJECT_CLUSTERS.CLUSTER_ID))
                                .join(CONTROL_PLANES)
                                        .on(CONTROL_PLANES.ID.eq(CLUSTERS.CONTROL_PLANE_ID))
                                .where(PROJECT_CLUSTERS.PROJECT_ID.eq(APPLICATIONSETS.PROJECT_ID))
                                .and(CONTROL_PLANES.NAME.eq(cpName))))
                .orderBy(APPLICATIONSETS.NAME)
                .fetch(r -> ApplicationSetItem.builder()
                        .name(r.get(APPLICATIONSETS.NAME))
                        .projectName(r.get(projectNameField))
                        .goTemplate(Boolean.TRUE.equals(r.get(APPLICATIONSETS.GO_TEMPLATE)))
                        .generatorSpec(jsonbUtils.fromJsonb(
                                r.get(APPLICATIONSETS.GENERATOR_SPEC), LIST_MAP_TYPE))
                        .templateSpec(jsonbUtils.fromJsonb(
                                r.get(APPLICATIONSETS.TEMPLATE_SPEC), MAP_TYPE))
                        .build());
    }
}
