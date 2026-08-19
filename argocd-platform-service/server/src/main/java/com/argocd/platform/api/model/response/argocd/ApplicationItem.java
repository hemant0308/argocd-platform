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
    /**
     * When {@code true}, the Helm chart adds {@code resources-finalizer.argocd.argoproj.io}
     * to the Application and the {@code argocd-platform/deletion-mode: hard} label.
     * This signals ArgoCD to cascade-delete all managed resources when the Application is pruned.
     *
     * <p>Set to {@code true} only for applications in {@code HARD_DELETE} state.
     * Applications in {@code SOFT_DELETE} or {@code AWAITING_PRUNE} state are excluded
     * from the plugin response entirely.
     */
    private boolean hardDelete;
}
