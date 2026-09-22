package com.vigil.core.domain;

import com.vigil.core.domain.interfaces.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record AnalysisCaseDiscovered(UUID eventId, UUID caseId, String actor, Instant occurredAt, String version)
        implements DomainEvent {
    public AnalysisCaseDiscovered {
        DomainEvent.validate(eventId, caseId, actor, occurredAt, version);
    }
}
