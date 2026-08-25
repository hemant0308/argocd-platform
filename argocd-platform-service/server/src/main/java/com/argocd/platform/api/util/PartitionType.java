package com.argocd.platform.api.util;

/**
 * Identifies which resource type a partition belongs to.
 * Used by {@code PartitionRepository.resolvePartitionId()} to determine
 * which partition table and resource table to query.
 */
public enum PartitionType {
    PROJECT,
    CLUSTER,
    APPLICATION,
    APPLICATION_SET
}
