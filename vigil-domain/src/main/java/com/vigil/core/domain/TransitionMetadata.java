package com.vigil.core.domain;

import java.time.Instant;
import java.util.Objects;

public record TransitionMetadata(
        String actor,
        String reason,
        Instant occuredAt
) {
    public TransitionMetadata {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Actor must not be blank.");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason must not be blank.");
        }

        Objects.requireNonNull(occuredAt, "Occured at must not be null.");
    }
}
