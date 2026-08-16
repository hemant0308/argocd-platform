package com.argocd.platform.api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationResponse {

    private UUID id;
    private String name;
    private UUID projectId;
    private UUID clusterId;
    private String status;
    private Long generation;
    private Integer applicationPartitionNumber;
    private List<Map<String, Object>> sources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
