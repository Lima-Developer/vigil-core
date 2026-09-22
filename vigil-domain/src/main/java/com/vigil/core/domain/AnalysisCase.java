package com.vigil.core.domain;

import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AnalysisCase {
    private final UUID caseId;
    private final Instant createdAt;
    private final List<CaseEvent> events = new ArrayList<>();
    private CaseStatus status;

    private AnalysisCase(UUID caseId, Instant createdAt) {
        this.caseId = Objects.requireNonNull(caseId, "CaseId must not be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt must not be null.");
        this.status = CaseStatus.DISCOVERED;
    }

    public static AnalysisCase discovered(UUID caseId, Instant createdAt) {
        return new AnalysisCase(caseId, createdAt);
    }

    public void triage(TransitionMetadata metadata) {
        Objects.requireNonNull(metadata, "Transition metadata must not be null.");

        if (status != CaseStatus.DISCOVERED) {
            throw new InvalidCaseTransitionException(status, CaseStatus.TRIAGED);
        }

        events.add(new CaseEvent(
                status,
                CaseStatus.TRIAGED,
                metadata.actor(),
                metadata.reason(),
                metadata.occuredAt()
        ));

        status = CaseStatus.TRIAGED;
    }

    public UUID caseId() {
        return caseId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<CaseEvent> events() {
        return List.copyOf(events);
    }

    public CaseStatus status() {
        return status;
    }
}
