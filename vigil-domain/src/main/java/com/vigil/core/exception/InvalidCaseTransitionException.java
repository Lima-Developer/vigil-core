package com.vigil.core.exception;

import com.vigil.core.domain.enums.CaseStatus;

public final class InvalidCaseTransitionException extends IllegalStateException {
    public InvalidCaseTransitionException(
            CaseStatus currentStatus,
            CaseStatus requestedStatus
    ) {
        super("Cannot transition case form %s to %s.".formatted(currentStatus, requestedStatus));
    }
}
