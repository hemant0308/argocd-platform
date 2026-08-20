package com.argocd.platform.api.task;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.util.DeletionMode;
import com.argocd.platform.api.util.PartitionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler that provides fallback advancement of the application deletion state machine.
 *
 * <h3>Primary path (event-driven)</h3>
 * {@code HARD_DELETE → AWAITING_PRUNE} is driven by
 * {@link com.argocd.platform.api.service.argocd.ArgoCDStatusService}
 * upon receiving an {@code on-application-partition-synced} notification from
 * {@code application-partition-{N}-{cp}} on the managed ArgoCD.  The notification carries
 * a {@code argocd-platform/generation} label whose value is compared against each hard-deleting
 * app's {@code deletion_partition_generation} to confirm — without a fixed delay — that the
 * finalizer-bearing manifest was actually applied to the control plane.
 *
 * <h3>Scheduler role</h3>
 * <ol>
 *   <li><b>Hard-delete fallback</b> ({@link #fallbackHardDeleteTimeout()}):
 *       Safety net for missed {@code on-application-partition-synced} notifications.
 *       Kicks in after {@code argocd.platform.deletion.hard-delete-fallback-seconds}
 *       (default 300 s, 5× the poll interval). By this time the notification is presumed
 *       lost (e.g. webhook delivery failure) and the finalizer is very likely already synced.
 *       Worst case: the finalizer was not synced and ArgoCD prunes the Application without
 *       cascade — orphaned resources result. This is the same risk accepted in the original
 *       fixed-delay design; the event-driven path eliminates it for the 99 % case.</li>
 *   <li><b>Soft-delete timeout</b> ({@link #timeoutSoftDelete()}):
 *       Fallback for a missed {@code on-deleted} notification. Applications without a
 *       finalizer have a brief deletion window; if the notification is not delivered this
 *       task tombstones the row after the configured delay.</li>
 * </ol>
 *
 * <p>Both methods run inside a single transaction each. {@link PartitionChangedEvent}
 * (hard-delete fallback only) is processed by
 * {@link com.argocd.platform.api.cache.listener.CacheInvalidationListener}
 * after the transaction commits ({@code TransactionPhase.AFTER_COMMIT}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeletionStateTransitionTask {

    private final ApplicationRepository applicationRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Fallback delay (seconds) before advancing a {@code HARD_DELETE} app to
     * {@code AWAITING_PRUNE} when the event-driven path fails (missed notification).
     *
     * <p>Default: 300 s (5 min, 10× the 30 s poll interval). By this time the
     * {@code resources-finalizer} is very likely synced even for a slow ArgoCD reconcile.
     * Keep this value well above the normal event latency (typically &lt; 60 s)
     * so the scheduler never races the event-driven path in healthy conditions.
     */
    @Value("${argocd.platform.deletion.hard-delete-fallback-seconds:300}")
    private long hardDeleteFallbackSeconds;

    /**
     * Fallback delay (seconds) before tombstoning a {@code SOFT_DELETE} app
     * whose {@code on-deleted} notification was not received.
     *
     * <p>Default: 120 s (4× the default 30 s poll interval).
     */
    @Value("${argocd.platform.deletion.soft-delete-timeout-seconds:120}")
    private long softDeleteTimeoutSeconds;

    /**
     * Fallback scheduler for hard-delete: advances {@code HARD_DELETE} apps to
     * {@code AWAITING_PRUNE} after {@link #hardDeleteFallbackSeconds} have elapsed
     * without the event-driven notification arriving.
     *
     * <p>Under normal conditions the {@code on-application-partition-synced} notification
     * arrives within seconds and this method finds no eligible candidates. It only acts
     * when the webhook is unreachable or the notification is permanently lost.
     *
     * <p>Publishes a {@link PartitionChangedEvent} for each transitioned app so the
     * plugin cache is invalidated and the app disappears from the next plugin response,
     * triggering ArgoCD to prune it with the {@code resources-finalizer}.
     *
     * <hr>
     * <h4>⚠ FAILOVER PARTITION RACE — CP-SCOPED PARTITIONS (Option B)</h4>
     *
     * <p>This method is the FALLBACK path for the {@code deletion_partition_generation} fence.
     * Under Option B, a HARD_DELETE app's cluster can be moved by
     * {@code FailoverBatchService.migrateBatch()} while the app is between
     * {@code HARD_DELETE} and {@code AWAITING_PRUNE}. This creates the following race window:
     *
     * <ol>
     *   <li>App enters HARD_DELETE state:
     *       {@code applications.deletion_partition_generation} is set to generation G,
     *       which is the generation of the app's current {@code application_partition_id}
     *       (call it Partition-CP1-N) at initiation time.</li>
     *   <li>{@code migrateBatch()} runs: {@code applications.application_partition_id} is
     *       updated to a new CP2-scoped partition (Partition-CP2-M).
     *       The stored generation G now refers to Partition-CP1-N, which the app no longer
     *       belongs to.</li>
     *   <li>ArgoCD syncs Partition-CP2-M at generation G'. The status service receives
     *       {@code on-application-partition-synced} with G'. It checks
     *       G' ≥ {@code deletion_partition_generation} (G). Since G' and G are from
     *       different partitions (different counters), this comparison is unreliable:
     *       G' &lt; G is plausible, causing the fence to stay closed forever for this app.</li>
     *   <li>The primary event-driven path ({@code ArgoCDStatusService}) never fires
     *       {@code HARD_DELETE → AWAITING_PRUNE} for this app. THIS METHOD — the fallback —
     *       eventually fires after {@link #hardDeleteFallbackSeconds}.</li>
     *   <li>Worst case: the {@code resources-finalizer} manifest was not yet synced to CP2
     *       when the fallback fires. ArgoCD prunes the Application without cascade →
     *       orphaned resources remain on CP2.</li>
     * </ol>
     *
     * <p><b>Current mitigation</b>: {@code FailoverBatchService.migrateBatch()} emits a
     * WARNING log (Step 1 in the method body) when any HARD_DELETE app's cluster is being
     * moved. Operators should monitor for this log and avoid scheduled failovers while
     * hard-deletes are in progress.
     *
     * <p><b>Future improvement</b>: block {@code migrateBatch()} from moving a cluster if any
     * of its apps are in HARD_DELETE state, OR implement cross-partition generation tracking
     * (fence on partition UUID + generation pair instead of bare generation integer) to make
     * the fence reliable after a partition reassignment.
     */
    @Scheduled(fixedDelayString = "${argocd.platform.deletion.check-interval-ms:3000000}")
    @Transactional
    public void fallbackHardDeleteTimeout() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(hardDeleteFallbackSeconds);
        List<ApplicationRepository.DeletionCandidate> candidates =
                applicationRepository.findByDeletionModeOlderThan(DeletionMode.HARD_DELETE.name(), cutoff);

        if (candidates.isEmpty()) return;

        log.warn("Deletion scheduler fallback: {} HARD_DELETE app(s) older than {} s without " +
                 "an on-application-partition-synced notification; forcing AWAITING_PRUNE transition. " +
                 "Verify webhook delivery to /internal/argocd/status.",
                candidates.size(), hardDeleteFallbackSeconds);

        for (ApplicationRepository.DeletionCandidate c : candidates) {
            int rows = applicationRepository.transitionDeletionMode(
                    c.id(), DeletionMode.HARD_DELETE.name(), DeletionMode.AWAITING_PRUNE.name());
            if (rows > 0) {
                log.warn("Deletion scheduler fallback: forced app '{}' ({}) HARD_DELETE → AWAITING_PRUNE " +
                         "after {} s timeout; invalidating plugin cache for partition {}",
                        c.name(), c.id(), hardDeleteFallbackSeconds, c.applicationPartitionId());
                eventPublisher.publishEvent(
                        new PartitionChangedEvent(this, c.applicationPartitionId(), PartitionType.APPLICATION));
            } else {
                log.debug("Deletion scheduler fallback: app '{}' ({}) HARD_DELETE → AWAITING_PRUNE skipped " +
                          "(concurrent modification — event-driven path likely won)", c.name(), c.id());
            }
        }
    }

    /**
     * Marks {@code SOFT_DELETE} apps as deleted after {@link #softDeleteTimeoutSeconds} have
     * elapsed, as a fallback for cases where the ArgoCD {@code on-deleted} notification was
     * not received.
     *
     * <p>The {@code on-deleted} event for Applications without a finalizer has a brief
     * delivery window (K8s sets then immediately clears deletionTimestamp). This timeout
     * ensures the soft-tombstone ({@code deleted_at}) is always set within a bounded period.
     *
     * <p>No {@link PartitionChangedEvent} is published — the app was already removed
     * from the plugin response when {@code SOFT_DELETE} was first set.
     */
    @Scheduled(fixedDelayString = "${argocd.platform.deletion.check-interval-ms:3000000}")
    @Transactional
    public void timeoutSoftDelete() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(softDeleteTimeoutSeconds);
        List<ApplicationRepository.DeletionCandidate> candidates =
                applicationRepository.findByDeletionModeOlderThan(DeletionMode.SOFT_DELETE.name(), cutoff);

        if (candidates.isEmpty()) return;

        log.debug("Deletion scheduler: {} SOFT_DELETE app(s) eligible for timeout tombstone", candidates.size());

        for (ApplicationRepository.DeletionCandidate c : candidates) {
            int rows = applicationRepository.markDeleted(c.name());
            if (rows > 0) {
                log.info("Deletion scheduler: timeout tombstone applied to soft-deleted app '{}' ({}); " +
                         "on-deleted notification was not received within {} s",
                        c.name(), c.id(), softDeleteTimeoutSeconds);
            }
        }
    }
}
