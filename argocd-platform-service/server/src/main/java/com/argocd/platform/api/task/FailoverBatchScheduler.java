package com.argocd.platform.api.task;

import com.argocd.platform.api.repository.FailoverRepository;
import com.argocd.platform.api.service.FailoverBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Scheduled poller that drives the failover batch state machine.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li><b>PENDING → AWAITING_BATCH_CONFIRMATION</b>: picks up newly created failover
 *       operations and kicks off their first batch migration.</li>
 *   <li><b>AWAITING_BATCH_CONFIRMATION → (next batch | COMPLETED | TIMED_OUT)</b>:
 *       polls pending-confirmation operations and either confirms, advances, or times
 *       them out based on application status and the batch timeout window.</li>
 * </ol>
 *
 * <h3>Concurrency model</h3>
 * <p>The scheduler itself is not {@code @Transactional}. It discovers candidate
 * operation IDs in a lightweight query (no lock), then delegates each to
 * {@link FailoverBatchService} where each operation is processed inside its own
 * {@code @Transactional} method that acquires a
 * {@code SELECT FOR UPDATE SKIP LOCKED} row lock. This design means:
 * <ul>
 *   <li>Failures in one operation are isolated — other operations in the same tick
 *       are unaffected.</li>
 *   <li>Multiple scheduler instances (e.g. replicas) can run simultaneously without
 *       duplicate processing — the lock ensures only one instance processes any given
 *       operation row at a time.</li>
 *   <li>If an operation is processed between the discovery query and the lock attempt
 *       (status changed by another instance), {@code lockOperationById} returns empty
 *       and the method exits silently.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <p>Poll interval is controlled by {@code argocd.platform.failover.poll-interval-ms}
 * (default: 5 000 ms). The interval is a <em>fixed delay</em> (measured from the end
 * of the previous execution), so a slow tick cannot cause overlapping runs.
 *
 * @see FailoverBatchService
 * @see com.argocd.platform.api.config.SchedulingConfig
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FailoverBatchScheduler {

    private final FailoverRepository failoverRepository;
    private final FailoverBatchService failoverBatchService;

    /**
     * Single poll tick: processes all discoverable PENDING and AWAITING_BATCH_CONFIRMATION
     * failover operations.
     *
     * <p>PENDING operations are processed first so that a newly created operation can
     * transition and begin confirmation in the same tick where possible. Errors from
     * individual operations are caught and logged — a single bad operation does not
     * block the others.
     */
    @Scheduled(fixedDelayString = "${argocd.platform.failover.poll-interval-ms:5000}")
    public void poll() {

        // --- Phase 1: advance PENDING → AWAITING_BATCH_CONFIRMATION ---
        List<UUID> pendingIds = failoverRepository.findPendingOperationIds();
        if (!pendingIds.isEmpty()) {
            log.debug("Failover scheduler: {} PENDING operation(s) discovered", pendingIds.size());
            for (UUID id : pendingIds) {
                try {
                    failoverBatchService.processPendingOperation(id);
                } catch (Exception e) {
                    log.error("Failover scheduler: error processing PENDING operation {} — will retry next tick",
                            id, e);
                }
            }
        }

        // --- Phase 2: poll AWAITING_BATCH_CONFIRMATION for confirmation / timeout ---
        List<UUID> awaitingIds = failoverRepository.findAwaitingConfirmationOperationIds();
        if (!awaitingIds.isEmpty()) {
            log.debug("Failover scheduler: {} AWAITING_BATCH_CONFIRMATION operation(s) discovered",
                    awaitingIds.size());
            for (UUID id : awaitingIds) {
                try {
                    failoverBatchService.processAwaitingOperation(id);
                } catch (Exception e) {
                    log.error("Failover scheduler: error processing AWAITING operation {} — will retry next tick",
                            id, e);
                }
            }
        }
    }
}
