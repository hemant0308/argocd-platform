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
public class ControlPlaneRequest {

    @NotBlank(message = "Control plane name is required")
    private String name;

    @NotBlank(message = "Server URL is required")
    private String server;

    /** ArgoCD web-UI base URL (e.g. https://argocd.example.com). Used for navigation links. */
    private String endpoint;
}
