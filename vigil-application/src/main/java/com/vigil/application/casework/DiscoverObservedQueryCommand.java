package com.vigil.application.casework;

import com.vigil.core.domain.ObservedQuery;

import java.util.Objects;

public record DiscoverObservedQueryCommand(ObservedQuery observedQuery, String idempotencyKey) {
    public DiscoverObservedQueryCommand {
        Objects.requireNonNull(observedQuery, "Observed query must not be null.");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank.");
        }
    }
}
