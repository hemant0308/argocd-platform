package com.argocd.platform.api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlPlaneResponse {

    private UUID id;
    private String name;
    private String server;
    private String status;
    /** ArgoCD web-UI base URL — used for navigation links in the management UI. */
    private String endpoint;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
