package com.vigil.application.port.out.interfaces;

import com.vigil.core.domain.PlanSummaryEvidencePayload;

public interface ExecutionPlanSource {
    PlanSummaryEvidencePayload collect(String selectText);
}
