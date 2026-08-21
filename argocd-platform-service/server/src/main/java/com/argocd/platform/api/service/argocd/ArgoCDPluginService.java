package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.cache.PluginExecutor;
import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
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
 *   <li>{@code cluster-partitions} — all cluster partitions; each entry includes
 *       {@code controlPlaneName} (CP-scoped, Option B). No extra params.</li>
 *   <li>{@code cluster-groups} — clusters in a CP-scoped partition; requires
 *       {@code partitionNumber} and {@code cpName}. Returns a single entry because
 *       each partition belongs to exactly one CP (Option B — no inner CP fan-out).</li>
 *   <li>{@code project-partitions} — all project partitions (global — unchanged).</li>
 *   <li>{@code project-groups} — projects in a partition grouped by control plane;
 *       requires {@code partitionNumber}. CP fan-out is intentional here because
 *       AppProjects are global and must be deployed to every CP hosting the project's clusters.
 *       Failover-safe: derived from {@code clusters.control_plane_id}.</li>
 *   <li>{@code application-partitions} — all application partitions; each entry includes
 *       {@code controlPlaneName} (CP-scoped, Option B). No extra params.</li>
 *   <li>{@code application-groups} — applications in a CP-scoped partition; requires
 *       {@code partitionNumber} and {@code cpName}. Returns a single entry (no inner
 *       CP fan-out — partition already scoped to one CP).</li>
 * </ul>
 *
 * <p><b>Helm dependency</b>: the top-level ApplicationSet template must forward
 * {@code controlPlaneName} from {@code cluster-partitions}/{@code application-partitions}
 * responses as {@code cpName} when calling {@code cluster-groups}/{@code application-groups}.
 * Resource names should use {@code {partitionNumber}-{controlPlaneName}} to remain unique
 * across CPs. See implementation plan Step 7 for Helm chart details.
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

    @Transactional(readOnly = true)
    public PluginGeneratorResponse execute(PluginGeneratorRequest request) {
        Map<String, String> params = (request.getInput() != null && request.getInput().getParameters() != null)
                ? request.getInput().getParameters()
                : Map.of();

        String resource = params.getOrDefault("resource", "");

        List<Map<String, Object>> parameters = switch (resource) {
            case "cluster-partitions"     -> clusterPartitions();
            case "cluster-groups"         -> clusterGroups(params);
            case "project-partitions"     -> projectPartitions();
            case "project-groups"         -> projectGroups(params);
            case "application-partitions" -> applicationPartitions();
            case "application-groups"     -> applicationGroups(params);
            default -> throw new InvalidRequestException(
                    "Unknown resource: '" + resource + "'. Supported values: " +
                    "cluster-partitions, cluster-groups, project-partitions, project-groups, " +
                    "application-partitions, application-groups");
        };

        return PluginGeneratorResponse.builder()
                .output(PluginGeneratorResponse.Output.builder()
                        .parameters(parameters)
                        .build())
                .build();
    }

    // =========================================================================
    // resource: cluster-partitions
    // Returns one entry per CP-scoped cluster partition. Each entry generates one
    // cluster-partition-{N}-{cpName} Application in the top-level ApplicationSet.
    // controlPlaneName is passed through as a parameter so the Helm chart can:
    //   a) name the Application uniquely: "{N}-{cpName}"
    //   b) forward cpName to cluster-groups as a lookup key
    // =========================================================================

    private List<Map<String, Object>> clusterPartitions() {
        return partitionService.findAllClusterPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("controlPlaneName", p.getControlPlaneName());
                    m.put("generation", p.getGeneration());
                    m.put("clusterCount", p.getClusterCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: cluster-groups
    // Returns ONE entry for the specified CP-scoped cluster partition.
    // Under Option B the partition belongs to exactly one CP — inner CP
    // fan-out (which existed in the global-partition architecture) is gone.
    //
    // Required params: partitionNumber (int), cpName (string)
    //
    // The Helm chart previously iterated over N entries (one per CP); it now
    // iterates over a single entry. The entry structure is identical so the
    // Helm template change is minimal (see implementation plan Step 7 / Helm note).
    // =========================================================================

    private List<Map<String, Object>> clusterGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        String cpName = getRequiredString(params, "cpName");

        UUID cpId = controlPlaneRepository.findByName(cpName)
                .orElseThrow(() -> new InvalidRequestException(
                        "No control plane found with name: " + cpName))
                .getId();

        UUID partitionId = partitionService.findClusterPartitionIdByCpAndNumber(cpId, partitionNumber, cpName)
                .orElseThrow(() -> new InvalidRequestException(
                        "No cluster partition found with partitionNumber=" + partitionNumber +
                        " and cpName=" + cpName));

        List<ClusterItem> clusters = clusterRepository.findByPartitionId(partitionId);

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

        return List.of(m);
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
    // Returns one entry per CP-scoped application partition. Each entry generates
    // one application-partition-{N}-{cpName} Application in the top-level ApplicationSet.
    // controlPlaneName is forwarded to application-groups as cpName.
    // =========================================================================

    private List<Map<String, Object>> applicationPartitions() {
        return partitionService.findAllApplicationPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("controlPlaneName", p.getControlPlaneName());
                    m.put("generation", p.getGeneration());
                    m.put("applicationCount", p.getApplicationCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // resource: application-groups
    // Returns ONE entry for the specified CP-scoped application partition.
    // Under Option B the partition belongs to exactly one CP — no inner CP fan-out.
    //
    // Required params: partitionNumber (int), cpName (string)
    //
    // The generation value travels in the application-partition-{N}-{cpName}
    // Application's argocd-platform/generation label so the status service can
    // race-safely confirm hard-deletes against deletion_partition_generation.
    // =========================================================================

    private List<Map<String, Object>> applicationGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        String cpName = getRequiredString(params, "cpName");

        UUID cpId = controlPlaneRepository.findByName(cpName)
                .orElseThrow(() -> new InvalidRequestException(
                        "No control plane found with name: " + cpName))
                .getId();

        UUID partitionId = partitionService.findApplicationPartitionIdByCpAndNumber(cpId, partitionNumber, cpName)
                .orElseThrow(() -> new InvalidRequestException(
                        "No application partition found with partitionNumber=" + partitionNumber +
                        " and cpName=" + cpName));

        // Partition generation — used by deletion fence check in DeletionStateTransitionTask.
        long partitionGeneration = partitionService.findApplicationPartitionGeneration(partitionId);

        List<ApplicationItem> applications = argoCDApplicationRepository.findByPartitionId(partitionId);

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
        // Same generation for all apps in this CP-scoped partition.
        m.put("generation", partitionGeneration);

        return List.of(m);
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

    private String getRequiredString(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Missing required parameter: " + key);
        }
        return value.trim();
    }
}
