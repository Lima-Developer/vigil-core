package com.vigil.core.domain;

import java.time.Instant;
import java.util.UUID;

public record EvidenceCollected(UUID eventId, UUID caseId, UUID evidenceId, String actor, Instant occurredAt,
                                String version) implements DomainEvent {
    public EvidenceCollected {
        DomainEvent.validate(eventId, caseId, actor, occurredAt, version);
        java.util.Objects.requireNonNull(evidenceId, "Evidence id must not be null.");
    }
}
