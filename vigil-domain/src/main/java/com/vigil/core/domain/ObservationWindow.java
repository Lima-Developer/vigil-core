package com.vigil.core.domain;

import java.time.Instant;

public record ObservationWindow(
        Instant startedAtExclusive,
        Instant endedAtInclusive
) {
    public ObservationWindow {
        if (startedAtExclusive == null) {
            throw new IllegalArgumentException("Started at must not be null.");
        }

        if (endedAtInclusive == null) {
            throw new IllegalArgumentException("Ended at must not be null.");
        }

        if (!endedAtInclusive.isAfter(startedAtExclusive)) {
            throw new IllegalArgumentException("Ended at must be after started at");
        }
    }

    public boolean contains(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("Instant must not be null.");
        }

        return instant.isAfter(startedAtExclusive) && !instant.isAfter(endedAtInclusive);
    }
}
