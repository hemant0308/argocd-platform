package com.argocd.platform.api.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationSourceRequest {

    @NotBlank(message = "Repository URL is required")
    private String repoUrl;

    @NotBlank(message = "Revision (branch/tag/commit) is required")
    private String revision;

    /** Path within the repository to the manifests. Mutually exclusive with chart. */
    private String path;

    /** Helm chart name. Mutually exclusive with path. */
    private String chart;

    /** Helm values (raw YAML string) or override values. */
    private String values;

    /**
     * Ordering index for multi-source applications.
     * Sources are applied in ascending order. Defaults to 0.
     */
    @Builder.Default
    private int sourceOrder = 0;
}
