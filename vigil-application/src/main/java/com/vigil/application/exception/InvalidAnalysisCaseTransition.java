package com.vigil.application.exception;

public final class InvalidAnalysisCaseTransition extends RuntimeException {
    public InvalidAnalysisCaseTransition(Throwable cause) {
        super("Analysis case transition is not allowed.", cause);
    }
}
