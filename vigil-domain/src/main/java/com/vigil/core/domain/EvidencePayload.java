package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;

public sealed interface EvidencePayload permits MetricEvidencePayload, PlanSummaryEvidencePayload,
        RelationMetadataEvidencePayload {

    EvidenceType type();
}
