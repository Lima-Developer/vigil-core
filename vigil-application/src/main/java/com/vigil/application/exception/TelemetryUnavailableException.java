package com.vigil.application.exception;

import com.vigil.application.preflight.CapabilityCode;
import java.util.Objects;

/** The target cannot provide statement telemetry. Names the missing capability, never a URL, credential or SQL. */
public final class TelemetryUnavailableException extends RuntimeException {
    private final CapabilityCode missing;

    public TelemetryUnavailableException(CapabilityCode missing) {
        this(missing, null);
    }

    public TelemetryUnavailableException(CapabilityCode missing, Throwable cause) {
        super("Telemetry unavailable, missing capability: " + Objects.requireNonNull(missing), cause);
        this.missing = missing;
    }

    public CapabilityCode missing() {
        return missing;
    }
}
