package com.vigil.application.health;

import java.time.Instant;

public record HealthStatus(
        String applicationVersion,
        String targetName,
        String environment,
        Instant checkedAt,
        boolean healthy
) {
}
