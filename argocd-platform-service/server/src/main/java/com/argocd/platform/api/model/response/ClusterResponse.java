package com.argocd.platform.api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterResponse {

    private UUID id;
    private String name;
    private String server;
    private String status;
    private UUID controlPlaneId;
    private String controlPlaneName;
    private Integer clusterPartitionNumber;

    /** Namespace names / glob patterns this cluster is scoped to. {@code null} = cluster-level. */
    private List<String> namespaces;

    /** Kubernetes-style labels attached to this cluster registration. */
    private Map<String, String> labels;

    /**
     * Authentication configuration used to connect to this cluster's API server.
     * Stored and returned as a free-form JSON object — any shape is accepted.
     * {@code null} when no auth is configured.
     */
    private Map<String, Object> auth;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
