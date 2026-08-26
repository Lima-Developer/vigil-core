package com.vigil.core.domain;

import java.time.Instant;

public record CaseEvent(
        CaseStatus fromStatus,
        CaseStatus toStatus,
        String actor,
        String reason,
        Instant occuredAt
) {

}
