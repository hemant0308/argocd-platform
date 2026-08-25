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
public class ApplicationSetResponse {

    private UUID id;
    private String name;
    private UUID projectId;
    private String projectName;
    private Integer partitionNumber;
    private List<Map<String, Object>> generators;
    private Map<String, Object> template;
    private boolean goTemplate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
