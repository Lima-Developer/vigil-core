package com.vigil.application.exception;

import java.util.UUID;

public final class CaseNotFound extends RuntimeException {
    public CaseNotFound(UUID caseId) {
        super("Analysis case not found: " + caseId);
    }
}
