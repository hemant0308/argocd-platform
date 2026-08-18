package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.model.request.argocd.ArgoCDStatusRequest;
import com.argocd.platform.api.repository.ApplicationRepository;
import com.argocd.platform.api.repository.ClusterRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import com.argocd.platform.api.util.ResourceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes ArgoCD notification status callbacks from
 * {@code POST /internal/argocd/status}.
 *
 * <h3>Routing</h3>
 * Driven by the {@code resourceType} field populated from the Application's
 * {@code argocd-platform/resource-type} label. All DB writes are single-query
 * operations — no pre-lookup of UUIDs is required by this service.
 *
 * <h3>Status derivation</h3>
 * <table border="1">
 *   <tr><th>syncStatus</th><th>healthStatus</th><th>DB status</th></tr>
 *   <tr><td>Synced</td><td>Healthy</td><td>ACTIVE</td></tr>
 *   <tr><td>*</td><td>Progressing</td><td>SYNCING</td></tr>
 *   <tr><td>*</td><td>Degraded</td><td>DEGRADED</td></tr>
 *   <tr><td>Failed</td><td>*</td><td>ERROR</td></tr>
 *   <tr><td>*</td><td>*</td><td>UNKNOWN</td></tr>
 * </table>
 *
 * <p><b>Important:</b> none of the update methods publish {@code PartitionChangedEvent}
 * or bump partition generation. Doing so would trigger ArgoCD to re-sync, which
 * would fire another notification, causing an infinite loop.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArgoCDStatusService {

    private final ClusterRepository clusterRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;

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
                int rows = projectRepository.updateStatusByPartitionNumber(
                        parsePartitionNumber(request.getPartitionNumber()),
                        status);
                if (rows == 0) {
                    log.warn("No projects found for partition={}; status event ignored",
                            request.getPartitionNumber());
                } else {
                    log.info("Updated status={} for {} project(s) in partition={} (last-write-wins)",
                            status, rows, request.getPartitionNumber());
                }
            }
            case "application" -> {
                int rows = applicationRepository.updateStatusByName(
                        request.getApplicationName(), status);
                if (rows == 0) {
                    log.warn("Application '{}' not found; status event ignored",
                            request.getApplicationName());
                } else {
                    log.info("Updated status={} for application '{}' ({} row(s))",
                            status, request.getApplicationName(), rows);
                }
            }
            default -> log.warn("Unknown resourceType='{}' for application '{}'; status event ignored",
                    request.getResourceType(), request.getApplicationName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status derivation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps ArgoCD sync + health status pair to a {@link ResourceStatus}.
     *
     * <p>Evaluation order matters — Progressing is checked before the sync status
     * so that an OutOfSync+Progressing app is reported as SYNCING rather than UNKNOWN.
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
