package com.vigil.core.domain;

import com.vigil.core.domain.enums.CaseStatus;

import java.time.Instant;

public record CaseEvent(
        CaseStatus fromStatus,
        CaseStatus toStatus,
        String actor,
        String reason,
        Instant occuredAt
) {

}
