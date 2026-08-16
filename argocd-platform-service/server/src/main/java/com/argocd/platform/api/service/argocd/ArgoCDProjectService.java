package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.response.argocd.ProjectItem;
import com.argocd.platform.api.model.response.argocd.ProjectPartitionResponse;
import com.argocd.platform.api.repository.PartitionRepository;
import com.argocd.platform.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArgoCDProjectService {

    private final PartitionRepository partitionRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectPartitionResponse> listPartitions() {
        return partitionRepository.findAllProjectPartitions();
    }

    /**
     * Returns all projects in a partition identified by either {@code partitionId} (UUID)
     * or {@code partitionNumber} (integer). {@code partitionId} takes precedence when both
     * are supplied. At least one must be non-null. An unrecognised value returns an empty list.
     */
    @Transactional(readOnly = true)
    public List<ProjectItem> listByPartition(UUID partitionId, Integer partitionNumber) {
        UUID resolvedId = resolvePartitionId(partitionId, partitionNumber);
        return projectRepository.findByPartitionId(resolvedId);
    }

    private UUID resolvePartitionId(UUID partitionId, Integer partitionNumber) {
        if (partitionId == null && partitionNumber == null) {
            throw new InvalidRequestException("Either partitionId or partitionNumber must be provided");
        }
        if (partitionId != null) {
            return partitionId;
        }
        return partitionRepository.findProjectPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "Invalid partitionNumber: no project partition found with number " + partitionNumber));
    }
}
