package com.vigil.core.domain;

import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.interfaces.DomainEvent;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AnalysisCase {
    private final UUID caseId;
    private final Instant createdAt;
    private final Optional<QueryFingerprint> queryFingerprint;
    private final List<CaseEvent> events = new ArrayList<>();
    private final List<Evidence> evidences = new ArrayList<>();
    private final List<DomainEvent> pendingDomainEvents = new ArrayList<>();
    private CaseStatus status;

    private AnalysisCase(UUID caseId, Instant createdAt) {
        this(caseId, createdAt, Optional.empty());
    }

    private AnalysisCase(UUID caseId, Instant createdAt, Optional<QueryFingerprint> queryFingerprint) {
        this.caseId = Objects.requireNonNull(caseId, "CaseId must not be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt must not be null.");
        this.queryFingerprint = Objects.requireNonNull(queryFingerprint, "Query fingerprint must not be null.");
        this.status = CaseStatus.DISCOVERED;
    }

    public static AnalysisCase discover(UUID caseId, Instant createdAt) {
        return new AnalysisCase(caseId, createdAt);
    }

    /**
     * Opens a case for an observed query while retaining only its opaque identity.
     */
    public static AnalysisCase discover(UUID caseId, Instant createdAt, ObservedQuery observedQuery) {
        Objects.requireNonNull(observedQuery, "Observed query must not be null.");
        return new AnalysisCase(caseId, createdAt, Optional.of(observedQuery.fingerprint()));
    }

    public static AnalysisCase discover(UUID caseId, Instant createdAt, DomainEventMetadata metadata) {
        var analysisCase = new AnalysisCase(caseId, createdAt);
        analysisCase.pendingDomainEvents.add(new AnalysisCaseDiscovered(
                metadata.eventId(), caseId, metadata.actor(), metadata.occurredAt(), metadata.version()
        ));
        return analysisCase;
    }

    public void triage(String reason, String actor, Instant occurredAt) {
        triage(new TransitionMetadata(actor, reason, occurredAt));
    }

    public void triage(String reason, DomainEventMetadata metadata) {
        triage(reason, metadata.actor(), metadata.occurredAt());
        pendingDomainEvents.add(new QueryTriaged(
                metadata.eventId(), caseId, metadata.actor(), metadata.occurredAt(), metadata.version()
        ));
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

    public void collectEvidence(Evidence evidence, String actor, Instant occurredAt) {
        Objects.requireNonNull(evidence, "Evidence must not be null.");
        var metadata = new TransitionMetadata(actor, "Evidence collected.", occurredAt);

        if (status != CaseStatus.TRIAGED) {
            throw new InvalidCaseTransitionException(status, CaseStatus.EVIDENCE_COLLECTED);
        }

        evidence.requireBelongsTo(caseId);

        if (evidences.stream().anyMatch(existing -> existing.id().equals(evidence.id()))) {
            throw new IllegalArgumentException("Evidence is already attached to this analysis case.");
        }

        evidences.add(evidence);
        events.add(new CaseEvent(
                status,
                CaseStatus.EVIDENCE_COLLECTED,
                metadata.actor(),
                metadata.reason(),
                metadata.occuredAt()
        ));
        status = CaseStatus.EVIDENCE_COLLECTED;
    }

    public void collectEvidence(Evidence evidence, DomainEventMetadata metadata) {
        collectEvidence(evidence, metadata.actor(), metadata.occurredAt());
        pendingDomainEvents.add(new EvidenceCollected(
                metadata.eventId(), caseId, evidence.id(), metadata.actor(), metadata.occurredAt(), metadata.version()
        ));
    }

    public List<DomainEvent> pullEvents() {
        var pulledEvents = List.copyOf(pendingDomainEvents);
        pendingDomainEvents.clear();
        return pulledEvents;
    }

    public UUID caseId() {
        return caseId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<QueryFingerprint> queryFingerprint() {
        return queryFingerprint;
    }

    public List<CaseEvent> events() {
        return List.copyOf(events);
    }

    public List<Evidence> evidences() {
        return List.copyOf(evidences);
    }

    public CaseStatus status() {
        return status;
    }
}
