package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.cache.event.PartitionChangedEvent;
import com.argocd.platform.api.model.request.argocd.ArgoCDStatusRequest;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.DeletionMode;
import com.argocd.platform.api.util.HealthStatus;
import com.argocd.platform.api.util.PartitionType;
import com.argocd.platform.api.util.ResourceStatus;
import com.argocd.platform.api.util.SyncStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Processes ArgoCD notification status callbacks from
 * {@code POST /internal/argocd/status}.
 *
 * <h3>Routing</h3>
 * Driven by the {@code resourceType} field populated from the Application's
 * {@code argocd-platform/resource-type} label. All DB writes are single-query
 * operations — no pre-lookup of UUIDs is required by this service.
 *
 * <h3>Status derivation (clusters and projects only)</h3>
 * <p>Clusters and projects use a composite {@link ResourceStatus} derived from the ArgoCD
 * sync+health pair. Applications use a different model: {@code applications.status} is set
 * to {@code ACTIVE} by the {@code application-partition} event path when the Application
 * object is created on the CP; {@code sync_status} and {@code health_status} are updated
 * independently by {@code application} events.
 * <table border="1">
 *   <tr><th>syncStatus</th><th>healthStatus</th><th>DB status (cluster/project)</th></tr>
 *   <tr><td>Synced</td><td>Healthy</td><td>ACTIVE</td></tr>
 *   <tr><td>*</td><td>Progressing</td><td>SYNCING</td></tr>
 *   <tr><td>*</td><td>Degraded</td><td>DEGRADED</td></tr>
 *   <tr><td>Failed</td><td>*</td><td>ERROR</td></tr>
 *   <tr><td>*</td><td>*</td><td>UNKNOWN</td></tr>
 * </table>
 *
 * <h3>Deletion completion (application only)</h3>
 * When {@code deletionTimestamp} is non-empty, the event is an {@code on-deleted}
 * notification — the application is being removed from K8s. The service calls
 * {@link ApplicationRepository#markDeleted(String)} which soft-tombstones the row
 * ({@code deleted_at = now()}) regardless of whether it was a soft or hard delete.
 *
 * <p><b>Important:</b> most update methods do not publish {@code PartitionChangedEvent}
 * because doing so would trigger ArgoCD to re-sync the same Application, which would
 * fire another notification creating an infinite loop.
 * The sole exception is {@code application-partition} handling: transitioning
 * {@code HARD_DELETE → AWAITING_PRUNE} intentionally publishes a
 * {@link PartitionChangedEvent} to invalidate the plugin cache so the app disappears
 * from the next plugin response and ArgoCD prunes it with the finalizer. This does
 * cause another sync of {@code application-partition-{N}-{cp}}, but that subsequent
 * {@code on-application-partition-synced} event finds no HARD_DELETE candidates
 * (they are now AWAITING_PRUNE) and is silently a no-op — the loop terminates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArgoCDStatusService {

    private final ClusterRepository clusterRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void processStatusEvent(ArgoCDStatusRequest request) {
        String status = deriveStatus(request.getSyncStatus(), request.getHealthStatus());

        log.debug("Processing ArgoCD status event: resourceType={}, app={}, partition={}, cp={}, sync={}, health={} → dbStatus={}",
                request.getResourceType(), request.getApplicationName(),
                request.getPartitionNumber(), request.getControlPlane(),
                request.getSyncStatus(), request.getHealthStatus(), status);

        switch (request.getResourceType()) {
            case "cluster" -> {
                int rows = clusterRepository.updateStatusByPartitionNumberAndControlPlaneName(
                        parsePartitionNumber(request.getPartitionNumber()),
                        request.getControlPlane(),
                        status);
                if (rows == 0) {
                    log.warn("No clusters found for partition={} cp='{}'; status event ignored",
                            request.getPartitionNumber(), request.getControlPlane());
                } else {
                    log.info("Updated status={} for {} cluster(s) in partition={} cp='{}'",
                            status, rows, request.getPartitionNumber(), request.getControlPlane());
                }
            }
            case "project" -> {
                // CP-scoped: only update projects that have ≥1 cluster on this CP.
                // Without the CP filter, a sync event from CP-X would overwrite the
                // status of projects that have no clusters on CP-X (not deployed there).
                int rows = projectRepository.updateStatusByPartitionNumberAndControlPlaneName(
                        parsePartitionNumber(request.getPartitionNumber()),
                        request.getControlPlane(),
                        status);
                if (rows == 0) {
                    log.warn("No projects found for partition={} cp='{}'; status event ignored",
                            request.getPartitionNumber(), request.getControlPlane());
                } else {
                    log.info("Updated status={} for {} project(s) in partition={} cp='{}' (last-write-wins)",
                            status, rows, request.getPartitionNumber(), request.getControlPlane());
                }
            }
            case "application" -> processApplicationEvent(request);
            case "application-partition" -> processApplicationPartitionEvent(request);
            default -> log.warn("Unknown resourceType='{}' for application '{}'; status event ignored",
                    request.getResourceType(), request.getApplicationName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Application-partition event routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles {@code on-application-partition-synced} events from
     * {@code application-partition-{N}-{cp}} Applications on the managed ArgoCD.
     *
     * <h3>Purpose</h3>
     * When a hard-delete is initiated the platform service:
     * <ol>
     *   <li>Bumps the partition generation to {@code m} and stores it as
     *       {@code applications.deletion_partition_generation}.</li>
     *   <li>Exposes {@code hardDelete: true} in the plugin response.</li>
     *   <li>Returns the new partition generation {@code m} in the response so
     *       ArgoCD carries it in the
     *       {@code argocd-platform/generation} label of the generated
     *       {@code application-partition-{N}-{cp}} Application.</li>
     * </ol>
     * ArgoCD detects the manifest change, syncs the finalizer to the control plane,
     * and fires this callback with {@code generation = m} in the label.
     *
     * <h3>Race safety</h3>
     * The condition {@code deletion_partition_generation ≤ syncedGeneration} is monotonically
     * sound: partition generation only increases.  If a stale sync at generation {@code m-1}
     * arrives before the HARD_DELETE is processed, the app's {@code deletion_partition_generation}
     * is {@code m > m-1} → not matched → not transitioned prematurely.  Only the sync at
     * generation {@code m} (or higher, if a concurrent change bumped it again) satisfies the
     * condition and triggers the transition.
     *
     * <h3>Loop termination</h3>
     * Transitioning to {@code AWAITING_PRUNE} publishes a {@link PartitionChangedEvent}, which
     * may cause {@code application-partition-{N}-{cp}} to re-sync (prune the Application).
     * The subsequent {@code on-application-partition-synced} fires but finds no
     * {@code HARD_DELETE} candidates (they are now {@code AWAITING_PRUNE}) → no-op → loop ends.
     *
     * @param request the notification payload
     */
    private void processApplicationPartitionEvent(ArgoCDStatusRequest request) {
        // Only act on fully healthy syncs. Failed/degraded events carry no guarantee
        // that the finalizer manifest was applied — do not advance the state machine.
        if (!"Synced".equals(request.getSyncStatus()) || !"Healthy".equals(request.getHealthStatus())) {
            log.debug("application-partition event ignored: not Synced+Healthy " +
                      "(app='{}', sync={}, health={})",
                    request.getApplicationName(), request.getSyncStatus(), request.getHealthStatus());
            return;
        }

        // Generation is absent for cluster/project/application events (no label → empty string).
        // Guard here so a misconfigured template doesn't cause a NumberFormatException.
        String generationStr = request.getGeneration();
        if (generationStr == null || generationStr.isBlank()) {
            log.warn("application-partition event missing generation label for app='{}'; " +
                     "HARD_DELETE → AWAITING_PRUNE transition skipped",
                    request.getApplicationName());
            return;
        }

        long syncedGeneration;
        try {
            syncedGeneration = Long.parseLong(generationStr.trim());
        } catch (NumberFormatException e) {
            log.warn("application-partition event has non-numeric generation='{}' for app='{}'; ignored",
                    generationStr, request.getApplicationName());
            return;
        }

        int partitionNumber = parsePartitionNumber(request.getPartitionNumber());
        String controlPlane = request.getControlPlane();

        List<ApplicationRepository.DeletionCandidate> candidates =
                applicationRepository.findHardDeleteByPartitionNumberAndCpUpToGeneration(
                        partitionNumber, controlPlane, syncedGeneration);

        if (candidates.isEmpty()) {
            log.debug("application-partition sync (partition={}, cp='{}', generation={}): " +
                      "no HARD_DELETE candidates eligible for AWAITING_PRUNE transition",
                    partitionNumber, controlPlane, syncedGeneration);
        } else {
            log.debug("application-partition sync (partition={}, cp='{}', generation={}): " +
                      "{} HARD_DELETE candidate(s) eligible for AWAITING_PRUNE transition",
                    partitionNumber, controlPlane, syncedGeneration, candidates.size());
        }

        for (ApplicationRepository.DeletionCandidate c : candidates) {
            int rows = applicationRepository.transitionDeletionMode(
                    c.id(), DeletionMode.HARD_DELETE.name(), DeletionMode.AWAITING_PRUNE.name());
            if (rows > 0) {
                log.info("Event-driven transition: app '{}' ({}) HARD_DELETE → AWAITING_PRUNE " +
                         "confirmed by partition-{} cp='{}' sync at generation {}",
                        c.name(), c.id(), partitionNumber, controlPlane, syncedGeneration);
                // Cache invalidation: app now excluded from plugin response →
                // ArgoCD prunes the Application with resources-finalizer → on-deleted fires.
                // This does cause one more application-partition sync, but subsequent events
                // find no HARD_DELETE candidates (now AWAITING_PRUNE) and are no-ops.
                eventPublisher.publishEvent(
                        new PartitionChangedEvent(this, c.applicationPartitionId(), PartitionType.APPLICATION));
            } else {
                log.debug("application-partition transition: app '{}' ({}) already transitioned " +
                          "(concurrent modification or scheduler beat us)", c.name(), c.id());
            }
        }

        // Mark all non-deleting applications in this partition on this CP as ACTIVE.
        // This is the "CREATED" confirmation signal for failover operations: the
        // application-partition ApplicationSet successfully deployed the Application
        // objects to this control plane, so the resource exists on the destination.
        int activatedRows = applicationRepository.updateStatusByPartitionNumberAndControlPlaneName(
                partitionNumber, controlPlane, ResourceStatus.ACTIVE.name());
        if (activatedRows > 0) {
            log.debug("application-partition sync (partition={}, cp='{}'): " +
                      "marked {} application(s) ACTIVE (CREATED confirmation)",
                    partitionNumber, controlPlane, activatedRows);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Application event routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Routes an application-scoped notification.
     *
     * <ul>
     *   <li>If {@code deletionTimestamp} is non-empty: this is an {@code on-deleted} event.
     *       Mark the application as deleted (soft-tombstone) regardless of deletion mode.
     *       Handles both soft-delete (no finalizer, immediate) and hard-delete (finalizer
     *       removed after cascade cleanup) completion paths.</li>
     *   <li>Otherwise: raw sync and health state are persisted to {@code sync_status} and
     *       {@code health_status} respectively. The update is scoped to the control plane
     *       in the notification payload — notifications from a CP that no longer owns the
     *       application's cluster (e.g. after failover) match zero rows and are silently
     *       ignored, preventing timestamp-gate false positives.</li>
     * </ul>
     *
     * <p>Note: {@code applications.status} (ACTIVE/UNKNOWN/…) is NOT updated here.
     * That field is owned by the {@code application-partition} sync path — it is set
     * to {@code ACTIVE} when the managed ArgoCD confirms the Application object was
     * deployed to the control plane.
     */
    private void processApplicationEvent(ArgoCDStatusRequest request) {
        String deletionTimestamp = request.getDeletionTimestamp();

        if (deletionTimestamp != null && !deletionTimestamp.isBlank()) {
            // on-deleted event: K8s has set a deletionTimestamp on the Application.
            // Soft-tombstone the row; this completes the deletion state machine.
            int rows = applicationRepository.markDeleted(request.getApplicationName());
            if (rows == 0) {
                log.warn("Application '{}' not found or already tombstoned; on-deleted event ignored",
                        request.getApplicationName());
            } else {
                log.info("Deletion complete: tombstoned application '{}' (deletionMode='{}', deletionTimestamp='{}')",
                        request.getApplicationName(),
                        request.getDeletionMode(),
                        deletionTimestamp);
            }
        } else {
            // Normal sync/health event: persist raw ArgoCD state to sync_status and health_status.
            // CP-scoped so that post-failover notifications from the old CP are silently ignored
            // (the application's cluster now points to the new CP → 0 rows matched).
            String syncStatus   = SyncStatus.fromArgoCD(request.getSyncStatus()).name();
            String healthStatus = HealthStatus.fromArgoCD(request.getHealthStatus()).name();

            int rows = applicationRepository.updateSyncAndHealthStatusByName(
                    request.getApplicationName(),
                    request.getControlPlane(),
                    syncStatus,
                    healthStatus);
            if (rows == 0) {
                log.warn("Application '{}' not found, in deletion state, or owned by a different CP (cp='{}'); " +
                         "sync/health event ignored",
                        request.getApplicationName(), request.getControlPlane());
            } else {
                log.info("Updated syncStatus={} healthStatus={} for application '{}' on cp='{}' ({} row(s))",
                        syncStatus, healthStatus, request.getApplicationName(), request.getControlPlane(), rows);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status derivation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps an ArgoCD sync + health status pair to a composite {@link ResourceStatus}.
     * Used for <strong>clusters and projects only</strong> — applications persist the
     * raw {@code sync_status} and {@code health_status} fields directly via
     * {@link ApplicationRepository#updateSyncAndHealthStatusByName}.
     *
     * <p>Evaluation order matters — Progressing is checked before the sync status
     * so that an OutOfSync+Progressing resource is reported as SYNCING rather than UNKNOWN.
     */
    static String deriveStatus(String syncStatus, String healthStatus) {
        if ("Synced".equals(syncStatus) && "Healthy".equals(healthStatus)) {
            return ResourceStatus.ACTIVE.name();
        }
        if ("Progressing".equals(healthStatus)) {
            return ResourceStatus.SYNCING.name();
        }
        if ("Degraded".equals(healthStatus)) {
            return ResourceStatus.DEGRADED.name();
        }
        if ("Failed".equals(syncStatus)) {
            return ResourceStatus.ERROR.name();
        }
        return ResourceStatus.UNKNOWN.name();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int parsePartitionNumber(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid partitionNumber in status event payload: '" + raw + "'");
        }
    }
}
