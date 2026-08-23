package com.argocd.platform.api.cache.listener;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Permanent no-op listener for ArgoCD ApplicationSet refresh notifications.
 *
 * <p>Active only when {@code argocd.platform.cache.enabled=true}.
 *
 * <h3>Architecture constraint — no outbound connections to ArgoCD</h3>
 * <p>The Router Service operates with a strict one-way call contract:
 * <b>ArgoCD always calls the Router Service; the Router Service never calls ArgoCD.</b>
 * This eliminates mutual TLS complexity, removes the need for ArgoCD API credentials
 * in the service, and prevents circular dependency during bootstrap (ArgoCD may not
 * yet be reachable when the service starts).
 *
 * <h3>Why no outbound call is needed</h3>
 * <p>Event-driven reconciliation is achieved entirely through the ArgoCD polling hierarchy:
 * <ol>
 *   <li>Every resource mutation bumps the affected partition's {@code generation} counter.</li>
 *   <li>Level 1 ApplicationSets ({@code requeueAfterSeconds: 10}) poll the
 *       {@code /&#42;/partitions} endpoints — tiny payload (partition numbers + generations).</li>
 *   <li>When Level 1 detects a changed generation it re-renders the Level 2 Application
 *       via Helm, passing the new {@code generation} as a Helm value.</li>
 *   <li>Level 2's Helm render changes the Level 3 ApplicationSet's
 *       {@code spec.generators[0].plugin.input.parameters.generation}, which is a
 *       <em>spec-level</em> change → ArgoCD increments {@code metadata.generation}
 *       → the ApplicationSet controller immediately starts a reconcile cycle.</li>
 *   <li>Level 3 calls {@code /api/v1/getparams.execute} → plugin returns fresh data
 *       from the (already invalidated) Redis cache.</li>
 * </ol>
 * <p>End-to-end propagation latency after a mutation: ≤ 10 s (Level 1 poll window).
 * Level 3's own {@code requeueAfterSeconds: 600} acts as a pure safety net.
 *
 * <p>This class is retained so the {@link CacheInvalidationListener} and the refresh
 * concern remain in separate classes for independent evolution; it exists as an
 * architectural marker, not dead code.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "argocd.platform.cache.enabled", havingValue = "true")
public class ArgoCDRefreshListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartitionChanged(PartitionChangedEvent event) {
        // Intentional no-op. ArgoCD reconciliation is driven entirely by generation
        // propagation through the Level 1 → Level 2 → Level 3 ApplicationSet hierarchy.
        // See class Javadoc for the full event-driven flow description.
        //
        // Architecture constraint: the Router Service MUST NOT make outbound calls to
        // ArgoCD. Any future notification mechanism must be implemented as an ArgoCD
        // webhook trigger (ArgoCD polling us), not as an API call from this service.
    }
}
