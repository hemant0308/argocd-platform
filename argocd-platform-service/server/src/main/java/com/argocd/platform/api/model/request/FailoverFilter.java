package com.argocd.platform.api.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cluster selection criteria for a failover operation.
 *
 * <h3>Filter semantics</h3>
 * <ul>
 *   <li>AND between different filter fields — all specified fields must match.</li>
 *   <li>OR between items in the same list field (e.g. multiple {@code labelSelectors} entries).</li>
 *   <li>AND within each {@code labelSelectors} entry — all key/value pairs must match.</li>
 *   <li>Label values are POSIX regex patterns (case-sensitive, unanchored, Postgres {@code ~}).</li>
 *   <li>At least one filter field must be non-empty (validated by the service).</li>
 *   <li>Clusters already assigned to {@code targetControlPlane} are always excluded.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailoverFilter {

    /**
     * Explicit cluster IDs to include. ANDed with other filter fields.
     */
    private List<UUID> clusterIds;

    /**
     * Explicit cluster names to include. ANDed with other filter fields.
     */
    private List<String> clusterNames;

    /**
     * Label selector list. Each entry is a map of {@code {key: regexValue}}.
     * Semantics: OR between entries, AND within each entry, values are
     * POSIX regex (Postgres {@code ~}, case-sensitive, unanchored).
     * Example: {@code [{"env":"prod","region":"us-east.*"},{"team":"platform"}]}
     * matches clusters where ({@code env ~ "prod"} AND {@code region ~ "us-east.*"})
     * OR {@code team ~ "platform"}.
     */
    private List<Map<String, String>> labelSelectors;

    /**
     * Restrict selection to clusters currently assigned to these control planes.
     * ANDed with other filter fields.
     */
    private List<String> sourceControlPlanes;
}
