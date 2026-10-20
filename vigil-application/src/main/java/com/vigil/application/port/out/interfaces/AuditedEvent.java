package com.vigil.application.port.out.interfaces;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only view of a persisted domain event: its type code and metadata, never a payload.
 */
public record AuditedEvent(String type, String actor, Instant occurredAt, String version) {
    public AuditedEvent {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Type must not be blank.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
        Objects.requireNonNull(occurredAt, "Occurred at must not be null.");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Version must not be blank.");
    }
}
