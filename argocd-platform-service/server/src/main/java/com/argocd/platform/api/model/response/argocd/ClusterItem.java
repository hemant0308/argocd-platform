package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterItem {
    private String name;
    private String server;
    /** Name of the control plane this cluster is assigned to. {@code null} if unassigned. */
    private String controlPlane;
    /**
     * Free-form auth object stored on the cluster, passed verbatim to the
     * ArgoCD Cluster Secret {@code stringData.config} field by the
     * cluster-registration Helm chart.
     */
    private Map<String, Object> config;
}
