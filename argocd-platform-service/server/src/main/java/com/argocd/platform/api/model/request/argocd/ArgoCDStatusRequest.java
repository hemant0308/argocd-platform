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
 *   <li>{@code application-partition} → event-driven HARD_DELETE → AWAITING_PRUNE transition;
 *       requires {@code generation} (partition generation from the Application label)</li>
 * </ul>
 *
 * <p>Deletion routing (application only):
 * When {@code deletionTimestamp} is non-empty the event is an {@code on-deleted} notification —
 * the service marks the application as deleted regardless of sync/health status.
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

    /**
     * RFC 3339 timestamp set by K8s when the Application deletion is initiated.
     * Non-empty only on {@code on-deleted} trigger events. Empty string for all other triggers.
     *
     * <p>When non-empty for {@code resourceType = "application"}, the service calls
     * {@code markDeleted(applicationName)} instead of the normal status update path.
     */
    private String deletionTimestamp;

    /**
     * Value of the {@code argocd-platform/deletion-mode} label on the Application.
     * {@code "hard"} for hard-delete Applications; empty string for soft-delete or active Applications.
     *
     * <p>Informational only — the deletion completion action ({@link #deletionTimestamp}) is the
     * authoritative signal; this field is logged for observability.
     */
    private String deletionMode;

    /**
     * Value of the {@code argocd-platform/generation} label on the Application.
     * Populated only for {@code resourceType = "application-partition"} notifications —
     * it carries the partition generation at the time of the sync so the status service
     * can confirm which hard-deleting apps had their finalizer manifest included in that sync.
     *
     * <p>Intentionally <em>not</em> {@code @NotBlank}: cluster/project/application events do not
     * set this label, so the field will be an empty string for those resource types.
     * Handlers must check for blank/null before parsing.
     */
    private String generation;
}
