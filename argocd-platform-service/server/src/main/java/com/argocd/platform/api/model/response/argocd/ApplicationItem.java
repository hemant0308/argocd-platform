package com.argocd.platform.api.model.response.argocd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationItem {
    private String name;
    private String project;
    private String cluster;
    private String controlPlane;
    /** Free-form ArgoCD source objects — stored and returned verbatim. */
    private List<Map<String, Object>> sources;
}
