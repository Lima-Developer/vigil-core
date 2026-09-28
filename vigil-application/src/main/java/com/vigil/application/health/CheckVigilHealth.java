package com.vigil.application.health;

import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.port.out.interfaces.Clock;

import java.time.Instant;
import java.util.Objects;

public final class CheckVigilHealth {

    private final Clock clock;
    private final String applicationVersion;

    public CheckVigilHealth(Clock clock, String applicationVersion) {
        this.clock = Objects.requireNonNull(clock, "Clock não pode ser nulo");

        if (applicationVersion == null || applicationVersion.isBlank()) {
            throw new IllegalArgumentException("applicationVersion não pode ser nula ou vazia");
        }

        this.applicationVersion = applicationVersion;
    }

    public HealthStatus check(VigilConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration não pode ser nula");

        Instant checkedAt = Objects.requireNonNull(
                clock.now(),
                "Clock não pode retornar um instante nulo"
        );

        return new HealthStatus(
                applicationVersion,
                configuration.targetName(),
                configuration.environment(),
                checkedAt,
                true
        );
    }
}
