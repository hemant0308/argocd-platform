package com.argocd.platform.api.cache.listener;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stub listener that will trigger an ArgoCD ApplicationSet refresh after a
 * partition-changing transaction commits.
 *
 * <p>Active only when {@code argocd.platform.cache.enabled=true}.
 *
 * <p><b>TODO:</b> Implement the ArgoCD API call to force an immediate ApplicationSet
 * reconcile cycle for the affected partition.  Until then, ArgoCD relies on its
 * own polling interval (configured in the ApplicationSet {@code requeueAfterSeconds}).
 * The cache TTL ({@code argocd.platform.cache.ttl-minutes}) provides a safety-net
 * upper bound on how long ArgoCD may observe stale plugin generator output.
 *
 * <p>Design note: this listener is intentionally separate from
 * {@link CacheInvalidationListener} so the two concerns — local cache eviction and
 * remote ArgoCD notification — can evolve independently.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
public class ArgoCDRefreshListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartitionChanged(PartitionChangedEvent event) {
        // TODO: call ArgoCD API to trigger ApplicationSet refresh for the affected partition.
        // Until implemented, ArgoCD reconciles on its own schedule.
        if (event.getPartitionId() == null) {
            log.debug("ArgoCD refresh stub: control-plane change — would refresh all ApplicationSets");
        } else {
            log.debug("ArgoCD refresh stub: partition {} ({}) changed — would refresh ApplicationSet",
                    event.getPartitionId(), event.getPartitionType());
        }
    }
}
