package com.vigil.core.domain;

import java.time.Instant;

public record ObservationWindow(
        Instant startedAt,
        Instant endedAt
) {
    public ObservationWindow
    {
        if (startedAt == null) {
            throw new IllegalArgumentException("Started at must not be null.");
        }

        if (endedAt == null) {
            throw new IllegalArgumentException("Ended at must not be null.");
        }

        if (!endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Ended at must be after started at");
        }
    }
}
