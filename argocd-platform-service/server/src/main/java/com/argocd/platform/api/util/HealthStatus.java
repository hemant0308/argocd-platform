package com.argocd.platform.api.util;

/**
 * ArgoCD health state stored in {@code applications.health_status}.
 *
 * <p>Persists the raw ArgoCD health state independently from the composite
 * {@link ResourceStatus} (which reflects whether the Application object
 * was created on the destination control plane).
 *
 * <ul>
 *   <li>{@code UNKNOWN}     — initial/unrecognised state (incl. ArgoCD {@code Missing})</li>
 *   <li>{@code HEALTHY}     — ArgoCD reports {@code Healthy}</li>
 *   <li>{@code DEGRADED}    — ArgoCD reports {@code Degraded}</li>
 *   <li>{@code PROGRESSING} — ArgoCD reports {@code Progressing}</li>
 * </ul>
 *
 * <p>Used as the confirmation criterion when a failover operation's
 * {@code successCondition} is {@code HEALTHY}.
 */
public enum HealthStatus {

    UNKNOWN,
    HEALTHY,
    DEGRADED,
    PROGRESSING;

    /**
     * Maps the raw ArgoCD health-status string to the platform enum value.
     * Unrecognised or null values (including {@code "Missing"} and {@code "Unknown"})
     * fall back to {@link #UNKNOWN}.
     *
     * @param argocdValue raw value from the ArgoCD notification template
     *                    (e.g. {@code "Healthy"}, {@code "Degraded"})
     */
    public static HealthStatus fromArgoCD(String argocdValue) {
        if (argocdValue == null) {
            return UNKNOWN;
        }
        return switch (argocdValue) {
            case "Healthy"     -> HEALTHY;
            case "Degraded"    -> DEGRADED;
            case "Progressing" -> PROGRESSING;
            default            -> UNKNOWN; // Missing, Unknown → UNKNOWN
        };
    }
}
