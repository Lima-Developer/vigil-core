package com.vigil.application.exception;

import java.util.UUID;

public final class ConcurrentCaseUpdate extends RuntimeException {
    public ConcurrentCaseUpdate(UUID caseId) {
        super("Concurrent update detected for analysis case: " + caseId);
    }
}
