package com.argocd.platform.api.service.argocd;

import com.argocd.platform.api.exception.InvalidRequestException;
import com.argocd.platform.api.model.response.argocd.ApplicationItem;
import com.argocd.platform.api.model.response.argocd.ApplicationPartitionResponse;
import com.argocd.platform.api.repository.ArgoCDApplicationRepository;
import com.argocd.platform.api.repository.PartitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArgoCDApplicationService {

    private final PartitionRepository partitionRepository;
    private final ArgoCDApplicationRepository argoCDApplicationRepository;

    @Transactional(readOnly = true)
    public List<ApplicationPartitionResponse> listPartitions() {
        return partitionRepository.findAllApplicationPartitions();
    }

    /**
     * Returns all applications in a partition identified by either {@code partitionId} (UUID)
     * or {@code partitionNumber} (integer). {@code partitionId} takes precedence when both
     * are supplied. At least one must be non-null. An unrecognised value returns an empty list.
     */
    @Transactional(readOnly = true)
    public List<ApplicationItem> listByPartition(UUID partitionId, Integer partitionNumber) {
        UUID resolvedId = resolvePartitionId(partitionId, partitionNumber);
        return argoCDApplicationRepository.findByPartitionId(resolvedId);
    }

    private UUID resolvePartitionId(UUID partitionId, Integer partitionNumber) {
        if (partitionId == null && partitionNumber == null) {
            throw new InvalidRequestException("Either partitionId or partitionNumber must be provided");
        }
        if (partitionId != null) {
            return partitionId;
        }
        return partitionRepository.findApplicationPartitionIdByNumber(partitionNumber)
                .orElseThrow(() -> new InvalidRequestException(
                        "Invalid partitionNumber: no application partition found with number " + partitionNumber));
    }
}
