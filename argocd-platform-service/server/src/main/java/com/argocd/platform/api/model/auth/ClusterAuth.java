package com.argocd.platform.api.model.auth;

import com.argocd.platform.api.validation.ValidClusterAuth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication configuration for a Kubernetes cluster API server.
 *
 * <p>The {@code type} discriminator determines which fields are required.
 * Validation is performed by {@link com.argocd.platform.api.validation.ClusterAuthValidator}.
 *
 * <h3>BEARER</h3>
 * <pre>
 *   { "type": "BEARER", "token": "&lt;service-account-token-or-kubeconfig-bearer-token&gt;" }
 * </pre>
 * Required fields: {@code token}
 *
 * <p>Future types will extend this class with additional fields; validation branches
 * in {@link com.argocd.platform.api.validation.ClusterAuthValidator} will cover them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ValidClusterAuth
public class ClusterAuth {

    /**
     * Authentication mechanism. Must not be {@code null} when an auth object is provided.
     */
    private AuthType type;

    // -------------------------------------------------------------------------
    // BEARER fields
    // -------------------------------------------------------------------------

    /**
     * Bearer token for Kubernetes API server authentication.
     * Required when {@code type == BEARER}.
     */
    private String token;

    // -------------------------------------------------------------------------
    // Future auth type fields go here — add fields + a validator branch in
    // ClusterAuthValidator when a new AuthType is introduced.
    // Example:
    //   private String certData;    // CERTIFICATE
    //   private String keyData;     // CERTIFICATE
    //   private String caData;      // CERTIFICATE (optional)
    // -------------------------------------------------------------------------
}
