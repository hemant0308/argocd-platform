package com.argocd.platform.api.util;

/**
 * Lifecycle status shared by all platform resources
 * (control planes, clusters, projects, applications).
 *
 * Resources start as UNKNOWN; additional states will be added
 * as the platform matures (e.g. ACTIVE, INACTIVE, DELETING).
 */
public enum ResourceStatus {

    UNKNOWN;

    // TODO: add ACTIVE, INACTIVE, DELETING, ERROR as lifecycle expands
}
