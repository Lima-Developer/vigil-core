package com.vigil.application.preflight;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PostgresPreflightReport(PostgresPreflightStatus status, Instant checkedAt, Integer serverVersionNum,
                                     String databaseName, List<CapabilityResult> capabilities) {
    public PostgresPreflightReport {
        Objects.requireNonNull(status);
        Objects.requireNonNull(checkedAt);
        capabilities = List.copyOf(capabilities);
    }
}
