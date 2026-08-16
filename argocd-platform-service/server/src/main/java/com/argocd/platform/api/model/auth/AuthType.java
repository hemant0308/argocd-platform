package com.argocd.platform.api.model.auth;

/**
 * Supported cluster authentication types.
 *
 * <p>Each value maps to a set of required fields in {@link ClusterAuth}:
 * <ul>
 *   <li>{@link #BEARER} — requires {@code token}</li>
 * </ul>
 *
 * <p>New auth types (e.g. CERTIFICATE, KUBECONFIG) will be added here with
 * corresponding required-field documentation as they are supported.
 */
public enum AuthType {
    BEARER
    // TODO: CERTIFICATE — requires certData + keyData (+ optional caData)
    // TODO: KUBECONFIG  — requires kubeconfigBase64
}
