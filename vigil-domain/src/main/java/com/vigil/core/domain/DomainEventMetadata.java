package com.vigil.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEventMetadata(UUID eventId, String actor, Instant occurredAt, String version) {
    public DomainEventMetadata {
        Objects.requireNonNull(eventId, "Event id must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
        Objects.requireNonNull(occurredAt, "Occurred at must not be null.");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Event version must not be blank.");
    }
}
