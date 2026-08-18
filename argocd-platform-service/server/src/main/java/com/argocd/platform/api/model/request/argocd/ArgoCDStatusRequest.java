package com.argocd.platform.api.model.request.argocd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload sent by ArgoCD notifications webhooks to
 * {@code POST /internal/argocd/status}.
 *
 * <p>All fields are sourced from the Application manifest via ArgoCD notification
 * template expressions — no name-pattern parsing is needed on the service side:
 *
 * <ul>
 *   <li>{@code resourceType} — from label {@code argocd-platform/resource-type}:
 *       {@code "cluster"}, {@code "project"}, or {@code "application"}</li>
 *   <li>{@code applicationName} — ArgoCD Application {@code .metadata.name}</li>
 *   <li>{@code partitionNumber} — from label {@code argocd-platform/partition-number}</li>
 *   <li>{@code controlPlane} — from label {@code argocd-platform/control-plane}</li>
 * </ul>
 *
 * <p>Routing by {@code resourceType}:
 * <ul>
 *   <li>{@code cluster} → update all clusters in {@code partitionNumber} on {@code controlPlane}</li>
 *   <li>{@code project} → update all projects in {@code partitionNumber} (last-write-wins)</li>
 *   <li>{@code application} → update single application by {@code applicationName}</li>
 * </ul>
 */
@Data
public class ArgoCDStatusRequest {

    /** Resource dimension: {@code "cluster"}, {@code "project"}, or {@code "application"}. */
    @NotBlank
    private String resourceType;

    /** ArgoCD Application name — used for application-level DB lookup. */
    @NotBlank
    private String applicationName;

    /** Partition number (string from K8s label). Used for cluster and project routing. */
    @NotBlank
    private String partitionNumber;

    /** Control-plane name. Used for cluster routing; ignored for project (last-write-wins). */
    @NotBlank
    private String controlPlane;

    /** ArgoCD sync status: {@code Synced}, {@code OutOfSync}, {@code Failed}, {@code Unknown}. */
    @NotBlank
    private String syncStatus;

    /** ArgoCD health status: {@code Healthy}, {@code Degraded}, {@code Progressing}, {@code Missing}, {@code Unknown}. */
    @NotBlank
    private String healthStatus;
}
