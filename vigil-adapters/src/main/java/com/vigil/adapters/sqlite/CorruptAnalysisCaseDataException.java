package com.vigil.adapters.sqlite;

/** Persisted data that cannot be turned into a valid aggregate: unknown code, bad UUID/instant, broken history. */
public final class CorruptAnalysisCaseDataException extends RuntimeException {
    public CorruptAnalysisCaseDataException(String message) {
        super(message);
    }

    public CorruptAnalysisCaseDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
