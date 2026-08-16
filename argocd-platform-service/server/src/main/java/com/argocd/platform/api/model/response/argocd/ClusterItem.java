package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterItem {
    private String name;
    private String server;
    /** Name of the control plane this cluster is assigned to. {@code null} if unassigned. */
    private String controlPlane;
}
