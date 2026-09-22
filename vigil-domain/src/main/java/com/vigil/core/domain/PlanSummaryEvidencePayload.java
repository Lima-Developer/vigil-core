package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.PlanNodeType;

import java.util.List;
import java.util.Objects;

public record PlanSummaryEvidencePayload(
        List<PlanNodeType> nodeTypes,
        long estimatedRows
) implements EvidencePayload {
    public PlanSummaryEvidencePayload {
        Objects.requireNonNull(nodeTypes, "Plan node types must not be null.");
        nodeTypes = List.copyOf(nodeTypes);

        if (nodeTypes.isEmpty()) {
            throw new IllegalArgumentException("Plan summary must contain at least one node type.");
        }

        if (estimatedRows < 0) {
            throw new IllegalArgumentException("Estimated rows must not be negative.");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.PLAN_SUMMARY;
    }
}
