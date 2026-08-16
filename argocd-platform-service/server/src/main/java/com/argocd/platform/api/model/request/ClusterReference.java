package com.argocd.platform.api.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A reference to a cluster by either its UUID or its name.
 * ID takes precedence over name when both are supplied.
 * At least one of the two fields must be non-null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterReference {

    /** Cluster UUID. Used directly when present; overrides name. */
    private UUID id;

    /** Cluster name. Used for lookup when id is absent. */
    private String name;
}
