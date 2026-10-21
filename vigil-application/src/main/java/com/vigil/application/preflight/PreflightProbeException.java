package com.vigil.application.preflight;

import java.util.Objects;

/** Failure category only; diagnostic details must not enter the report. */
public final class PreflightProbeException extends RuntimeException {
    private final CapabilityCode code;

    public PreflightProbeException(CapabilityCode code) {
        super("Falha na inspeção PostgreSQL");
        this.code = Objects.requireNonNull(code);
    }

    public CapabilityCode code() {
        return code;
    }
}
