package com.argocd.platform.api.util;

/**
 * ArgoCD sync state stored in {@code applications.sync_status}.
 *
 * <p>Persists the raw ArgoCD sync state independently from the composite
 * {@link ResourceStatus} (which reflects whether the Application object
 * was created on the destination control plane).
 *
 * <ul>
 *   <li>{@code UNKNOWN}    — initial/unrecognised state</li>
 *   <li>{@code SYNCED}     — ArgoCD reports {@code Synced}</li>
 *   <li>{@code OUT_OF_SYNC} — ArgoCD reports {@code OutOfSync}</li>
 *   <li>{@code FAILED}     — ArgoCD reports {@code Failed}</li>
 * </ul>
 *
 * <p>Used as the confirmation criterion when a failover operation's
 * {@code successCondition} is {@code SYNCED}.
 */
public enum SyncStatus {

    UNKNOWN,
    SYNCED,
    OUT_OF_SYNC,
    FAILED;

    /**
     * Maps the raw ArgoCD sync-status string to the platform enum value.
     * Unrecognised or null values fall back to {@link #UNKNOWN}.
     *
     * @param argocdValue raw value from the ArgoCD notification template
     *                    (e.g. {@code "Synced"}, {@code "OutOfSync"})
     */
    public static SyncStatus fromArgoCD(String argocdValue) {
        if (argocdValue == null) {
            return UNKNOWN;
        }
        return switch (argocdValue) {
            case "Synced"    -> SYNCED;
            case "OutOfSync" -> OUT_OF_SYNC;
            case "Failed"    -> FAILED;
            default          -> UNKNOWN;
        };
    }
}
