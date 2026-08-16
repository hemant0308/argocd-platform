package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.repository.ArgoCDApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ControlPlaneRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li>{@code cluster-partitions} — all cluster partitions (no extra params)</li>
 *   <li>{@code cluster-groups} — clusters in a partition grouped by control plane;
 *       requires {@code partitionNumber}</li>
 *   <li>{@code project-partitions} — all project partitions (no extra params)</li>
 *   <li>{@code project-groups} — projects in a partition fanned out per control plane;
 *       requires {@code partitionNumber}. Every CP receives the full project list
 *       because AppProjects must exist on all control planes.</li>
 *   <li>{@code application-partitions} — all application partitions (no extra params)</li>
 *   <li>{@code application-groups} — applications in a partition grouped by control plane;
 *       requires {@code partitionNumber}. Each entry carries the full application list
 *       (with sources) for that CP. Apps without a CP assignment are excluded.</li>
 * </ul>
 *
 * <p>Parameter map values are native JSON types (string, integer, array, object) —
 * the ArgoCD Plugin Generator protocol (v2.6+) supports non-string values.
 * Arrays are consumed in the Helm chart via {@code range} without {@code fromJson}.
 */
@Service
@RequiredArgsConstructor
public class ArgoCDPluginService {

    private final PartitionRepository partitionRepository;
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

    // -------------------------------------------------------------------------
    // resource: cluster-partitions
    // Returns one entry per cluster partition. Each entry generates one
    // cluster-partition-NNN Application in the top-level ApplicationSet.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> clusterPartitions() {
        return partitionRepository.findAllClusterPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("clusterCount", p.getClusterCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // resource: cluster-groups
    // Returns one entry per control plane that has clusters in the partition.
    // Clusters without a control plane assignment are excluded (they cannot
    // be deployed to any CP). Each entry generates one cluster-partition-NNN-CP
    // Application that deploys cluster Secrets to the target control plane.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> clusterGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        UUID partitionId = partitionRepository.findClusterPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No cluster partition found with partitionNumber: " + partitionNumber));

        List<ClusterItem> clusters = clusterRepository.findByPartitionId(partitionId);

        // Group by controlPlane (preserve insertion order); skip unassigned clusters.
        // One entry per CP — the Helm chart uses fromJsonArray to range over clusters.
        Map<String, List<ClusterItem>> byCp = clusters.stream()
                .filter(c -> c.getControlPlane() != null)
                .collect(Collectors.groupingBy(
                        ClusterItem::getControlPlane,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return byCp.entrySet().stream()
                .map(entry -> {
                    List<Map<String, Object>> minimalClusters = entry.getValue().stream()
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
                    m.put("controlPlane", entry.getKey());
                    m.put("clusters", minimalClusters);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // resource: project-partitions
    // Returns one entry per project partition including the full list of
    // control plane names. The Helm chart uses this list to create one
    // ApplicationSet per CP (fan-out so every CP gets all AppProjects).
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> projectPartitions() {
        List<String> cpNames = controlPlaneRepository.findAll().stream()
                .map(cp -> cp.getName())
                .collect(Collectors.toList());

        return partitionRepository.findAllProjectPartitions().stream()
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

    // -------------------------------------------------------------------------
    // resource: project-groups
    // Returns one entry per control plane, each carrying the full project list
    // for the given partition. Unlike clusters (assigned to a specific CP),
    // AppProjects must exist on every control plane — so every CP entry receives
    // the complete project list. One entry per CP generates one
    // project-partition-NNN-CP Application that deploys AppProjects to that CP.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> projectGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        UUID partitionId = partitionRepository.findProjectPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No project partition found with partitionNumber: " + partitionNumber));

        List<ProjectItem> projectItems = projectRepository.findByPartitionId(partitionId);
        List<Map<String, String>> minimalProjects = projectItems.stream()
                .map(p -> Map.of("name", p.getName()))
                .collect(Collectors.toList());

        List<String> cpNames = controlPlaneRepository.findAll().stream()
                .map(cp -> cp.getName())
                .collect(Collectors.toList());

        return cpNames.stream()
                .map(cpName -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", partitionNumber);
                    m.put("controlPlane", cpName);
                    m.put("projects", minimalProjects);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // resource: application-partitions
    // Returns one entry per application partition. Each entry generates one
    // application-partition-NNN Application in the top-level ApplicationSet.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> applicationPartitions() {
        return partitionRepository.findAllApplicationPartitions().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", p.getPartitionNumber());
                    m.put("generation", p.getGeneration());
                    m.put("applicationCount", p.getApplicationCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // resource: application-groups
    // Returns one entry per control plane that has applications in the partition.
    // Like cluster-groups, applications are assigned to a specific cluster which
    // has a control plane — so we group by CP. Apps without a CP are excluded.
    // Each entry carries the full application list (with sources) for that CP.
    // One entry per CP generates one application-partition-NNN-CP Application
    // that deploys ArgoCD Application resources to the target control plane.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> applicationGroups(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        UUID partitionId = partitionRepository.findApplicationPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No application partition found with partitionNumber: " + partitionNumber));

        List<ApplicationItem> applications = argoCDApplicationRepository.findByPartitionId(partitionId);

        // Group by controlPlane (preserve insertion order); skip apps without a CP.
        // One entry per CP — the Helm chart uses fromJsonArray to range over applications.
        Map<String, List<ApplicationItem>> byCp = applications.stream()
                .filter(a -> a.getControlPlane() != null)
                .collect(Collectors.groupingBy(
                        ApplicationItem::getControlPlane,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return byCp.entrySet().stream()
                .map(entry -> {
                    List<Map<String, Object>> minimalApps = entry.getValue().stream()
                            .map(a -> {
                                Map<String, Object> am = new LinkedHashMap<>();
                                am.put("name", a.getName());
                                am.put("project", a.getProject());
                                am.put("cluster", a.getCluster());
                                am.put("sources", a.getSources()); // free-form maps, passed verbatim
                                return am;
                            })
                            .collect(Collectors.toList());

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("partitionNumber", partitionNumber);
                    m.put("controlPlane", entry.getKey());
                    m.put("applications", minimalApps);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
