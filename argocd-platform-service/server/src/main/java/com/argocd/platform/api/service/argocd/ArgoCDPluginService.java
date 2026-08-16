package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.request.argocd.PluginGeneratorRequest;
import com.argocd.platform.api.model.response.argocd.ClusterItem;
import com.argocd.platform.api.model.response.argocd.PluginGeneratorResponse;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
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
 *   <li>{@code project-partitions} — all project partitions with control planes list
 *       (no extra params)</li>
 *   <li>{@code projects} — flat project list for a partition; requires
 *       {@code partitionNumber}</li>
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

    @Transactional(readOnly = true)
    public PluginGeneratorResponse execute(PluginGeneratorRequest request) {
        Map<String, String> params = (request.getInput() != null && request.getInput().getParameters() != null)
                ? request.getInput().getParameters()
                : Map.of();

        String resource = params.getOrDefault("resource", "");

        List<Map<String, Object>> parameters = switch (resource) {
            case "cluster-partitions" -> clusterPartitions();
            case "cluster-groups"     -> clusterGroups(params);
            case "project-partitions" -> projectPartitions();
            case "projects"           -> projects(params);
            default -> throw new InvalidRequestException(
                    "Unknown resource: '" + resource + "'. Supported values: " +
                    "cluster-partitions, cluster-groups, project-partitions, projects");
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
                    List<Map<String, String>> minimalClusters = entry.getValue().stream()
                            .map(c -> Map.of("name", c.getName(), "server", c.getServer()))
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
    // resource: projects
    // Returns ONE entry for the given partition containing all project names.
    // The per-CP ApplicationSet uses this single entry to create one
    // Application per partition on its CP. That Application deploys
    // charts/appproject which loops over the list to create AppProject resources.
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> projects(Map<String, String> params) {
        int partitionNumber = getRequiredInt(params, "partitionNumber");
        UUID partitionId = partitionRepository.findProjectPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "No project partition found with partitionNumber: " + partitionNumber));

        List<ProjectItem> projectItems = projectRepository.findByPartitionId(partitionId);
        List<Map<String, String>> minimalProjects = projectItems.stream()
                .map(p -> Map.of("name", p.getName()))
                .collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partitionNumber", partitionNumber);
        m.put("projects", minimalProjects);
        return List.of(m);
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
