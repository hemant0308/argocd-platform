package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.cache.PluginExecutor;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ApplicationSetItem;
import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.repository.ApplicationSetRepository;
import com.argocd.platform.api.repository.ArgoCDApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.service.PartitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dispatches ArgoCD ApplicationSet Plugin Generator requests by {@code resource} parameter.
 *
 * <p>Supported resources:
 * <ul>
 *   <li>{@code cluster-partitions} — all cluster partitions (globally unique partition_number).
 *       No extra params. One entry per partition.</li>
 *   <li>{@code cluster-groups} — clusters in a global partition grouped by control plane
 *       (CP fan-out). Requires {@code partitionNumber}. Returns one entry per CP that has
 *       at least one cluster in the partition. Cluster-to-CP association is derived from
 *       {@code clusters.control_plane_id} at query time — never stored on the partition.</li>
 *   <li>{@code project-partitions} — all project partitions (global — unchanged).</li>
 *   <li>{@code project-groups} — projects in a partition grouped by control plane;
 *       requires {@code partitionNumber}. CP fan-out: one entry per CP hosting the
 *       project's clusters. Failover-safe: derived from {@code clusters.control_plane_id}.</li>
 *   <li>{@code application-partitions} — all application partitions (globally unique
 *       partition_number). No extra params. One entry per partition.</li>
 *   <li>{@code application-groups} — applications in a global partition grouped by control
 *       plane (CP fan-out). Requires {@code partitionNumber}. Returns one entry per CP that
 *       has at least one application in the partition.</li>
 *   <li>{@code applicationset-partitions} — all applicationset partitions (globally unique
 *       partition_number). No extra params. One entry per partition.</li>
 *   <li>{@code applicationset-groups} — applicationsets in a global partition filtered by
 *       CP via project fan-out. Requires {@code partitionNumber}. Returns one entry per CP
 *       that hosts ≥1 cluster belonging to each applicationset's project.</li>
 * </ul>
 *
 * <p><b>Architectural rule (permanent):</b> Control planes are stateless. The CP-to-resource
 * mapping is always derived from {@code clusters.control_plane_id} at query time.
 * Partition tables carry no {@code control_plane_id} column.
 *
 * <p><b>One-way call contract:</b> ArgoCD always calls the Router Service; the Router
 * Service MUST NEVER make outbound calls to ArgoCD.
 *
 * <p>Parameter map values are native JSON types (string, integer, array, object) —
 * the ArgoCD Plugin Generator protocol (v2.6+) supports non-string values.
 */
@Service
@RequiredArgsConstructor
public class ArgoCDPluginService implements PluginExecutor {

    private final PartitionService partitionService;
    private final ClusterRepository clusterRepository;
    private final ProjectRepository projectRepository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final ArgoCDApplicationRepository argoCDApplicationRepository;
    private final ApplicationSetRepository applicationSetRepository;

    @Transactional(readOnly = true)
    public PluginGeneratorResponse execute(PluginGeneratorRequest request) {
        Map<String, String> params = (request.getInput() != null && request.getInput().getParameters() != null)
                ? request.getInput().getParameters()
                : Map.of();

        String resource = params.getOrDefault("resource", "");

        List<Map<String, Object>> parameters = switch (resource) {
            case "cluster-partitions"        -> clusterPartitions();
            case "cluster-groups"            -> clusterGroups(params);
            case "project-partitions"        -> projectPartitions();
            case "project-groups"            -> projectGroups(params);
            case "application-partitions"    -> applicationPartitions();
            case "application-groups"        -> applicationGroups(params);
            case "applicationset-partitions" -> applicationSetPartitions();
            case "applicationset-groups"     -> applicationSetGroups(params);
            default -> throw new InvalidRequestException(
                    "Unknown resource: '" + resource + "'. Supported values: " +
                    "cluster-partitions, cluster-groups, project-partitions, project-groups, " +
                    "application-partitions, application-groups, " +
                    "applicationset-partitions, applicationset-groups");
        };

        return PluginGeneratorResponse.builder()
                .output(PluginGeneratorResponse.Output.builder()
                        .parameters(parameters)
                        .build())
                .build();
    }

    // =========================================================================
    // resource: cluster-partitions
    // Returns one entry per globally-unique cluster partition. Partition numbers
    // are unique across all control planes (Option A). No controlPlaneName field.
    // =========================================================================

    private List<Map<String, Object>> clusterPartitions() {
        return partitionService.findAllClusterPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("clusterCount", p.getClusterCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: cluster-groups
    // CP fan-out: returns one entry per control plane that has ≥1 cluster in the
    // given global partition. The CP-to-cluster mapping is derived from
    // clusters.control_plane_id at query time (ClusterItem.controlPlane).
    //
    // Required params: partitionNumber (int)
    //
    // This mirrors the projectGroups pattern, extended to clusters.
    // Failover-safe: when a cluster moves CPs, clusters.control_plane_id updates —
    // the next plugin poll automatically produces the correct CP fan-out entries.
    // =========================================================================

    private List<Map<String, Object>> clusterGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");

        UUID partitionId = partitionService.findClusterPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No cluster partition found with partitionNumber=" + partitionNumber));

        List<ClusterItem> allClusters = clusterRepository.findByPartitionId(partitionId);

        // Group clusters by their assigned control plane, preserving insertion order.
        Map<String, List<ClusterItem>> byControlPlane = allClusters.stream()
                .filter(c -> c.getControlPlane() != null)
                .collect(Collectors.groupingBy(ClusterItem::getControlPlane,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<ClusterItem>> entry : byControlPlane.entrySet()) {
            String cpName = entry.getKey();
            List<ClusterItem> clusters = entry.getValue();

            List<Map<String, Object>> minimalClusters = clusters.stream()
                    .map(c -> {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("name", c.getName());
                        cm.put("server", c.getServer());
                        cm.put("config", c.getConfig());
                        return cm;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partitionNumber", partitionNumber);
            m.put("controlPlane", cpName);
            m.put("clusters", minimalClusters);
            result.add(m);
        }
        return result;
    }

    // =========================================================================
    // resource: project-partitions
    // Returns one entry per project partition including the full list of
    // control plane names. Unchanged — project partitions are global.
    // =========================================================================

    private List<Map<String, Object>> projectPartitions() {
        List<String> cpNames = controlPlaneRepository.findAll().stream()
                .map(cp -> cp.getName())
                .collect(Collectors.toList());

        return partitionService.findAllProjectPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("projectCount", p.getProjectCount());
                    m.put("controlPlanes", cpNames);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: project-groups
    // Returns one entry per control plane that has at least one project in the
    // given partition with a cluster assigned to it. Unchanged — project
    // partitions remain global; the CP fan-out here is intentional (projects
    // must be deployed to every CP that hosts their clusters).
    //
    // Cluster-aware fan-out (failover safety):
    //   AppProjects only exist on a CP if that CP hosts ≥1 of the project's clusters.
    //   When a cluster moves (failover), the CP mapping updates automatically via
    //   clusters.control_plane_id — no extra bookkeeping needed.
    // =========================================================================

    private List<Map<String, Object>> projectGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        UUID partitionId = partitionService.findProjectPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No project partition found with partitionNumber: " + partitionNumber));

        List<String> cpNames = controlPlaneRepository.findAll().stream()
                .map(cp -> cp.getName())
                .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String cpName : cpNames) {
            // CP-filtered: only projects with ≥1 cluster on this CP; cluster list
            // scoped to this CP so AppProject destinations are always correct.
            List<ProjectItem> projectItems =
                    projectRepository.findByPartitionIdAndControlPlaneName(partitionId, cpName);

            if (projectItems.isEmpty()) {
                // No projects → no entry → ApplicationSet creates no Application for this CP.
                continue;
            }

            List<Map<String, Object>> minimalProjects = projectItems.stream()
                    .map(p -> {
                        List<Map<String, Object>> clusterMaps = p.getClusters() == null ? List.of()
                                : p.getClusters().stream()
                                        .map(c -> {
                                            Map<String, Object> cm = new LinkedHashMap<>();
                                            cm.put("name", c.getName());
                                            // null/empty → Helm template emits namespace: '*'
                                            cm.put("namespaces",
                                                    c.getNamespaces() != null ? c.getNamespaces() : List.of());
                                            return cm;
                                        })
                                        .collect(Collectors.toList());

                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("name", p.getName());
                        pm.put("clusters", clusterMaps);
                        return pm;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partitionNumber", partitionNumber);
            m.put("controlPlane", cpName);
            m.put("projects", minimalProjects);
            result.add(m);
        }
        return result;
    }

    // =========================================================================
    // resource: application-partitions
    // Returns one entry per globally-unique application partition (Option A).
    // No controlPlaneName field — CP association is derived at query time.
    // =========================================================================

    private List<Map<String, Object>> applicationPartitions() {
        return partitionService.findAllApplicationPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("applicationCount", p.getApplicationCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: application-groups
    // CP fan-out: returns one entry per control plane that has ≥1 application in
    // the given global partition. Applications' CP is derived from their cluster's
    // control_plane_id (ApplicationItem.controlPlane) — never from the partition.
    //
    // Required params: partitionNumber (int)
    //
    // The generation value in each entry is the partition's current generation —
    // the same value across all CP entries for the same partition, consistent with
    // the global partition model. The deletion fence check in DeletionStateTransitionTask
    // compares applications.deletion_partition_generation against this value.
    // =========================================================================

    private List<Map<String, Object>> applicationGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");

        UUID partitionId = partitionService.findApplicationPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No application partition found with partitionNumber=" + partitionNumber));

        // Partition generation — used by deletion fence check in DeletionStateTransitionTask.
        // Same for all CPs: the generation is per-partition, not per-CP.
        long partitionGeneration = partitionService.findApplicationPartitionGeneration(partitionId);

        List<ApplicationItem> allApplications =
                argoCDApplicationRepository.findByPartitionId(partitionId);

        // Group applications by their assigned control plane, preserving insertion order.
        Map<String, List<ApplicationItem>> byControlPlane = allApplications.stream()
                .filter(a -> a.getControlPlane() != null)
                .collect(Collectors.groupingBy(ApplicationItem::getControlPlane,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<ApplicationItem>> entry : byControlPlane.entrySet()) {
            String cpName = entry.getKey();
            List<ApplicationItem> applications = entry.getValue();

            List<Map<String, Object>> minimalApps = applications.stream()
                    .map(a -> {
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("name", a.getName());
                        am.put("project", a.getProject());
                        am.put("cluster", a.getCluster());
                        am.put("sources", a.getSources()); // free-form maps, passed verbatim
                        am.put("hardDelete", a.isHardDelete()); // drives conditional finalizer in Helm chart
                        return am;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partitionNumber", partitionNumber);
            m.put("controlPlane", cpName);
            m.put("applications", minimalApps);
            m.put("generation", partitionGeneration);
            result.add(m);
        }
        return result;
    }

    // =========================================================================
    // resource: applicationset-partitions
    // Returns one entry per globally-unique applicationset partition (Option A).
    // No controlPlaneName field — CP association is derived at query time via
    // project → project_clusters → clusters → control_planes.
    // =========================================================================

    private List<Map<String, Object>> applicationSetPartitions() {
        return partitionService.findAllApplicationSetPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("applicationSetCount", p.getApplicationSetCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: applicationset-groups
    // Project-based CP fan-out: returns one entry per control plane that has ≥1
    // cluster belonging to any applicationset's project in the given partition.
    //
    // Fan-out join path (mirrors projectGroups):
    //   applicationsets → project_id → project_clusters → clusters → control_planes
    //
    // Required params: partitionNumber (int)
    //
    // Failover-safe: when a cluster moves CPs, clusters.control_plane_id updates —
    // the next plugin poll automatically produces the correct CP fan-out entries.
    // =========================================================================

    private List<Map<String, Object>> applicationSetGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");

        UUID partitionId = partitionService.findApplicationSetPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No applicationset partition found with partitionNumber=" + partitionNumber));

        long partitionGeneration =
                partitionService.findApplicationSetPartitionGeneration(partitionId);

        List<String> cpNames = controlPlaneRepository.findAll().stream()
                .map(cp -> cp.getName())
                .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String cpName : cpNames) {
            List<ApplicationSetItem> appSets =
                    applicationSetRepository.findByPartitionIdAndControlPlaneName(
                            partitionId, cpName);

            if (appSets.isEmpty()) {
                // No applicationsets for this CP — no entry emitted, no Application created.
                continue;
            }

            List<Map<String, Object>> minimalAppSets = appSets.stream()
                    .map(a -> {
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("name", a.getName());
                        am.put("projectName", a.getProjectName());
                        am.put("goTemplate", a.isGoTemplate());
                        am.put("generatorSpec", a.getGeneratorSpec());
                        am.put("templateSpec", a.getTemplateSpec());
                        return am;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partitionNumber", partitionNumber);
            m.put("controlPlane", cpName);
            m.put("applicationSets", minimalAppSets);
            m.put("generation", partitionGeneration);
            result.add(m);
        }
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int getRequiredInt(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Missing required parameter: " + key);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(
                    "Invalid value for parameter '" + key + "': '" + value + "' is not a valid integer");
        }
    }
}
