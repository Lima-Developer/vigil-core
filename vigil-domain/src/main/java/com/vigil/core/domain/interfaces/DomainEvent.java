package com.vigil.core.domain.interfaces;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface DomainEvent {
    UUID eventId();
    UUID caseId();
    String actor();
    Instant occurredAt();
    String version();

    static void validate(UUID eventId, UUID caseId, String actor, Instant occurredAt, String version) {
        Objects.requireNonNull(eventId, "Event id must not be null.");
        Objects.requireNonNull(caseId, "Case id must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
        Objects.requireNonNull(occurredAt, "Occurred at must not be null.");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Event version must not be blank.");
    }
}
