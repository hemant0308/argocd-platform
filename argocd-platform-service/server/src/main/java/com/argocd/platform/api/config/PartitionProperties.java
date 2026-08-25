package com.argocd.platform.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurable target sizes for each partition dimension.
 * When an existing partition reaches its target size, a new partition is created.
 * These are soft targets — existing partitions are never rebalanced.
 *
 * Configured via:
 * <pre>
 * argocd:
 *   partition:
 *     project-target-size: 100
 *     cluster-target-size: 100
 *     application-target-size: 100
 *     application-set-target-size: 100
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "argocd.partition")
@Data
public class PartitionProperties {

    /** Maximum number of projects per partition before a new one is created. */
    private int projectTargetSize = 100;

    /** Maximum number of clusters per partition before a new one is created. */
    private int clusterTargetSize = 100;

    /** Maximum number of applications per partition before a new one is created. */
    private int applicationTargetSize = 100;

    /** Maximum number of user-defined ApplicationSets per partition before a new one is created. */
    private int applicationSetTargetSize = 100;
}
