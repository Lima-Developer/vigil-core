package com.vigil.application.preflight;

import java.util.Objects;

public record CapabilityResult(CapabilityCode code, CapabilityStatus status, String remediation) {
    public CapabilityResult {
        Objects.requireNonNull(code);
        Objects.requireNonNull(status);
        Objects.requireNonNull(remediation);
    }
}
