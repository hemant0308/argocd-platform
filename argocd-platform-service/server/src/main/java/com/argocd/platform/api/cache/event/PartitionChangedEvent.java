package com.argocd.platform.api.cache.event;

import com.argocd.platform.api.util.PartitionType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Domain event published inside a transaction whenever a partition's data changes.
 *
 * <p>Delivered to listeners <em>after the transaction commits</em> via
 * {@link org.springframework.transaction.event.TransactionalEventListener}.
 *
 * <p>Fields may be {@code null} to signal a "clear all" invalidation — used when
 * a control-plane change affects data across all partition types simultaneously.
 * Listeners check for null and respond with a full cache wipe in that case.
 *
 * <p>Published unconditionally by services so that non-cache listeners (such as
 * ArgoCD ApplicationSet refresh stubs) receive the same event without coupling
 * to the cache feature flag.
 */
@Getter
public class PartitionChangedEvent extends ApplicationEvent {

    /**
     * UUID of the partition whose data changed.
     * {@code null} when all partitions should be invalidated (e.g. a control-plane mutation).
     */
    private final UUID partitionId;

    /**
     * Resource type of the changed partition.
     * {@code null} when {@code partitionId} is {@code null}.
     */
    private final PartitionType partitionType;

    /**
     * @param source        the publishing bean (passed to {@link ApplicationEvent})
     * @param partitionId   UUID of the affected partition, or {@code null} for "clear all"
     * @param partitionType type of the partition, or {@code null} for "clear all"
     */
    public PartitionChangedEvent(Object source, UUID partitionId, PartitionType partitionType) {
        super(source);
        this.partitionId = partitionId;
        this.partitionType = partitionType;
    }

}
