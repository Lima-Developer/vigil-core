package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public record PlanSummaryEvidencePayload(
        List<PlanNodeType> nodeTypes,
        long estimatedRows,
        Optional<BigDecimal> estimatedTotalCost
) implements EvidencePayload {
    public PlanSummaryEvidencePayload(List<PlanNodeType> nodeTypes, long estimatedRows) {
        this(nodeTypes, estimatedRows, Optional.empty());
    }

    public PlanSummaryEvidencePayload {
        Objects.requireNonNull(nodeTypes, "Plan node types must not be null.");
        nodeTypes = List.copyOf(nodeTypes);

        if (nodeTypes.isEmpty()) {
            throw new IllegalArgumentException("Plan summary must contain at least one node type.");
        }

        Objects.requireNonNull(estimatedTotalCost, "Estimated total cost must not be null.");
        estimatedTotalCost.ifPresent(cost -> {
            if (cost.signum() < 0) throw new IllegalArgumentException("Estimated total cost must not be negative.");
        });
        if (estimatedRows < 0) {
            throw new IllegalArgumentException("Estimated rows must not be negative.");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.PLAN_SUMMARY;
    }
}
