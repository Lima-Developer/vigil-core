package com.vigil.core.domain;

import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.interfaces.DomainEvent;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    private long version;

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

    /**
     * Rebuilds a persisted case without running commands, so no new events are produced and none is pending.
     * The history must be a valid transition chain from {@link CaseStatus#DISCOVERED} ending at {@code status},
     * and evidences must belong to the case, have unique ids and exist only once evidence was collected.
     *
     * @param version persisted concurrency version; {@code 0} means never persisted
     */
    public static AnalysisCase reconstitute(
            UUID caseId,
            Instant createdAt,
            Optional<QueryFingerprint> queryFingerprint,
            CaseStatus status,
            long version,
            List<CaseEvent> events,
            List<Evidence> evidences
    ) {
        Objects.requireNonNull(status, "Status must not be null.");
        Objects.requireNonNull(events, "Events must not be null.");
        Objects.requireNonNull(evidences, "Evidences must not be null.");
        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative.");
        }

        var analysisCase = new AnalysisCase(caseId, createdAt, queryFingerprint);
        var current = CaseStatus.DISCOVERED;
        for (var event : events) {
            Objects.requireNonNull(event, "Case event must not be null.");
            new TransitionMetadata(event.actor(), event.reason(), event.occuredAt());
            if (event.fromStatus() != current || !isAllowedTransition(event.fromStatus(), event.toStatus())) {
                throw new IllegalArgumentException("Case history is not a valid transition chain.");
            }
            current = event.toStatus();
        }
        if (current != status) {
            throw new IllegalArgumentException("Case status does not match the end of its history.");
        }

        var evidenceIds = new java.util.HashSet<UUID>();
        for (var evidence : evidences) {
            Objects.requireNonNull(evidence, "Evidence must not be null.").requireBelongsTo(caseId);
            if (!evidenceIds.add(evidence.id())) {
                throw new IllegalArgumentException("Evidence ids must be unique within a case.");
            }
        }
        if (evidences.isEmpty() == (status == CaseStatus.EVIDENCE_COLLECTED)) {
            throw new IllegalArgumentException("Evidences must exist exactly when evidence was collected.");
        }

        analysisCase.events.addAll(events);
        analysisCase.evidences.addAll(evidences);
        analysisCase.status = status;
        analysisCase.version = version;
        return analysisCase;
    }

    private static boolean isAllowedTransition(CaseStatus from, CaseStatus to) {
        return (from == CaseStatus.DISCOVERED && to == CaseStatus.TRIAGED)
                || (from == CaseStatus.TRIAGED && to == CaseStatus.EVIDENCE_COLLECTED);
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

    /** Pending domain events, without draining them. Persist first, then call {@link #confirmPersisted}. */
    public List<DomainEvent> pendingEvents() {
        return List.copyOf(pendingDomainEvents);
    }

    /**
     * Records a committed save: adopts the persisted version and drops the pending events that were stored.
     * Must only be called after the transaction committed; consumers must not drain events with
     * {@link #pullEvents()} before persisting.
     */
    public void confirmPersisted(long persistedVersion, Collection<UUID> persistedEventIds) {
        Objects.requireNonNull(persistedEventIds, "Persisted event ids must not be null.");
        if (persistedVersion < version) {
            throw new IllegalArgumentException("Persisted version must not be lower than the current version.");
        }
        pendingDomainEvents.removeIf(event -> persistedEventIds.contains(event.eventId()));
        version = persistedVersion;
    }

    /** Concurrency version: {@code 0} until first persisted, then the persisted revision. */
    public long version() {
        return version;
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
