package com.vigil.core.domain;

import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.ValidationOutcome;
import com.vigil.core.domain.interfaces.DomainEvent;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate that owns the complete, append-only lifecycle of one investigation.
 * Every public transition is intentionally named and records actor, rationale
 * and instant in a {@link CaseEvent}; no generic public transitionTo exists.
 */
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

    public static AnalysisCase discover(UUID caseId, Instant createdAt) { return new AnalysisCase(caseId, createdAt); }

    public static AnalysisCase discover(UUID caseId, Instant createdAt, ObservedQuery observedQuery) {
        Objects.requireNonNull(observedQuery, "Observed query must not be null.");
        return new AnalysisCase(caseId, createdAt, Optional.of(observedQuery.fingerprint()));
    }

    public static AnalysisCase discover(UUID caseId, Instant createdAt, ObservedQuery observedQuery,
                                        DomainEventMetadata metadata) {
        Objects.requireNonNull(metadata, "Domain event metadata must not be null.");
        var analysisCase = discover(caseId, createdAt, observedQuery);
        analysisCase.pendingDomainEvents.add(new AnalysisCaseDiscovered(
                metadata.eventId(), caseId, metadata.actor(), metadata.occurredAt(), metadata.version()));
        return analysisCase;
    }

    public static AnalysisCase discover(UUID caseId, Instant createdAt, DomainEventMetadata metadata) {
        Objects.requireNonNull(metadata, "Domain event metadata must not be null.");
        var analysisCase = new AnalysisCase(caseId, createdAt);
        analysisCase.pendingDomainEvents.add(new AnalysisCaseDiscovered(
                metadata.eventId(), caseId, metadata.actor(), metadata.occurredAt(), metadata.version()));
        return analysisCase;
    }

    /** Rebuilds a stored aggregate after validating its complete transition chain. */
    public static AnalysisCase reconstitute(UUID caseId, Instant createdAt, Optional<QueryFingerprint> queryFingerprint,
                                            CaseStatus status, long version, List<CaseEvent> events,
                                            List<Evidence> evidences) {
        Objects.requireNonNull(status, "Status must not be null.");
        Objects.requireNonNull(events, "Events must not be null.");
        Objects.requireNonNull(evidences, "Evidences must not be null.");
        if (version < 0) throw new IllegalArgumentException("Version must not be negative.");
        var result = new AnalysisCase(caseId, createdAt, queryFingerprint);
        var current = CaseStatus.DISCOVERED;
        for (var event : events) {
            Objects.requireNonNull(event, "Case event must not be null.");
            new TransitionMetadata(event.actor(), event.reason(), event.occuredAt());
            if (event.fromStatus() != current || !isAllowedTransition(current, event.toStatus()))
                throw new IllegalArgumentException("Case history is not a valid transition chain.");
            current = event.toStatus();
        }
        if (current != status) throw new IllegalArgumentException("Case status does not match the end of its history.");
        var ids = new java.util.HashSet<UUID>();
        for (var evidence : evidences) {
            Objects.requireNonNull(evidence, "Evidence must not be null.").requireBelongsTo(caseId);
            if (!ids.add(evidence.id())) throw new IllegalArgumentException("Evidence ids must be unique within a case.");
        }
        if (requiresEvidence(status) && evidences.isEmpty())
            throw new IllegalArgumentException("Evidence must exist after collection.");
        if (!requiresEvidence(status) && !evidences.isEmpty())
            throw new IllegalArgumentException("Evidence cannot exist before collection.");
        result.events.addAll(events);
        result.evidences.addAll(evidences);
        result.status = status;
        result.version = version;
        return result;
    }

    public void triage(String reason, String actor, Instant occurredAt) { triage(new TransitionMetadata(actor, reason, occurredAt)); }
    public void triage(String reason, DomainEventMetadata metadata) {
        triage(reason, metadata.actor(), metadata.occurredAt());
        pendingDomainEvents.add(new QueryTriaged(metadata.eventId(), caseId, metadata.actor(), metadata.occurredAt(), metadata.version()));
    }
    public void triage(TransitionMetadata metadata) { transition(CaseStatus.TRIAGED, metadata); }

    public void collectEvidence(Evidence evidence, String actor, Instant occurredAt) {
        Objects.requireNonNull(evidence, "Evidence must not be null.");
        requireCurrent(CaseStatus.TRIAGED, CaseStatus.EVIDENCE_COLLECTED);
        evidence.requireBelongsTo(caseId);
        if (evidences.stream().anyMatch(item -> item.id().equals(evidence.id())))
            throw new IllegalArgumentException("Evidence is already attached to this analysis case.");
        evidences.add(evidence);
        transition(CaseStatus.EVIDENCE_COLLECTED, new TransitionMetadata(actor, "Evidence collected.", occurredAt));
    }
    public void collectEvidence(Evidence evidence, DomainEventMetadata metadata) {
        collectEvidence(evidence, metadata.actor(), metadata.occurredAt());
        pendingDomainEvents.add(new EvidenceCollected(metadata.eventId(), caseId, evidence.id(), metadata.actor(), metadata.occurredAt(), metadata.version()));
    }

    /**
     * Records that a safe, policy-approved context was produced for the case. The transition is only reachable with a
     * {@link SanitizationResult.Safe}; a {@code Blocked} result cannot be passed, so the case never advances on refused
     * content. The safe context itself is not stored (the aggregate never holds SQL).
     */
    public void sanitize(SanitizationResult.Safe safeContext, TransitionMetadata metadata) {
        Objects.requireNonNull(safeContext, "Sanitized context must not be null.");
        transition(CaseStatus.SANITIZED, metadata);
    }
    /** Records an evidence-grounded diagnostic; rationale identifies its approved artifact. */
    public void diagnose(TransitionMetadata metadata) { transition(CaseStatus.DIAGNOSED, metadata); }
    /** Records a bounded, testable optimization hypothesis. */
    public void proposeHypothesis(TransitionMetadata metadata) { transition(CaseStatus.HYPOTHESIS_PROPOSED, metadata); }
    /** Starts validation only after a sandbox profile and reason are recorded. */
    public void startValidation(TransitionMetadata metadata) { transition(CaseStatus.VALIDATING, metadata); }
    /** Records one of the three mutually exclusive sandbox outcomes. */
    public void recordValidation(ValidationOutcome outcome, TransitionMetadata metadata) {
        Objects.requireNonNull(outcome, "Validation outcome must not be null.");
        transition(CaseStatus.valueOf(outcome.name()), metadata);
    }
    /** Prepares an evidence-linked recommendation for human review. */
    public void prepareRecommendation(TransitionMetadata metadata) {
        if (status != CaseStatus.VALIDATED && status != CaseStatus.INVALIDATED && status != CaseStatus.INCONCLUSIVE)
            throw new InvalidCaseTransitionException(status, CaseStatus.AWAITING_HUMAN);
        transition(CaseStatus.AWAITING_HUMAN, metadata);
    }
    /** Human approval is recorded only; it never executes DDL or DML. */
    public void approve(TransitionMetadata metadata) { transition(CaseStatus.APPROVED, metadata); }
    public void reject(TransitionMetadata metadata) { transition(CaseStatus.REJECTED, metadata); }
    public void requestChanges(TransitionMetadata metadata) { transition(CaseStatus.CHANGES_REQUESTED, metadata); }
    /** Preserves the full history while re-opening a case for a new investigation pass. */
    public void retriage(TransitionMetadata metadata) { transition(CaseStatus.TRIAGED, metadata); }
    /** Controlled closure; an archived case cannot transition again. */
    public void archive(TransitionMetadata metadata) { transition(CaseStatus.ARCHIVED, metadata); }

    private void transition(CaseStatus target, TransitionMetadata metadata) {
        Objects.requireNonNull(metadata, "Transition metadata must not be null.");
        requireCurrentFor(target);
        events.add(new CaseEvent(status, target, metadata.actor(), metadata.reason(), metadata.occuredAt()));
        status = target;
    }

    private void requireCurrent(CaseStatus expected, CaseStatus target) {
        if (status != expected) throw new InvalidCaseTransitionException(status, target);
    }

    private void requireCurrentFor(CaseStatus target) {
        if (!isAllowedTransition(status, target)) throw new InvalidCaseTransitionException(status, target);
    }

    private static boolean requiresEvidence(CaseStatus status) { return status != CaseStatus.DISCOVERED && status != CaseStatus.TRIAGED; }

    private static boolean isAllowedTransition(CaseStatus from, CaseStatus to) {
        return switch (from) {
            case DISCOVERED -> to == CaseStatus.TRIAGED || to == CaseStatus.ARCHIVED;
            case TRIAGED -> to == CaseStatus.EVIDENCE_COLLECTED || to == CaseStatus.ARCHIVED;
            case EVIDENCE_COLLECTED -> to == CaseStatus.SANITIZED || to == CaseStatus.ARCHIVED;
            case SANITIZED -> to == CaseStatus.DIAGNOSED || to == CaseStatus.ARCHIVED;
            case DIAGNOSED -> to == CaseStatus.HYPOTHESIS_PROPOSED || to == CaseStatus.ARCHIVED;
            case HYPOTHESIS_PROPOSED -> to == CaseStatus.VALIDATING || to == CaseStatus.ARCHIVED;
            case VALIDATING -> to == CaseStatus.VALIDATED || to == CaseStatus.INVALIDATED || to == CaseStatus.INCONCLUSIVE || to == CaseStatus.ARCHIVED;
            case VALIDATED, INVALIDATED, INCONCLUSIVE -> to == CaseStatus.AWAITING_HUMAN || to == CaseStatus.ARCHIVED;
            case AWAITING_HUMAN -> to == CaseStatus.APPROVED || to == CaseStatus.REJECTED || to == CaseStatus.CHANGES_REQUESTED || to == CaseStatus.ARCHIVED;
            case CHANGES_REQUESTED -> to == CaseStatus.TRIAGED || to == CaseStatus.ARCHIVED;
            case APPROVED, REJECTED -> to == CaseStatus.ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public List<DomainEvent> pullEvents() { var result = List.copyOf(pendingDomainEvents); pendingDomainEvents.clear(); return result; }
    public List<DomainEvent> pendingEvents() { return List.copyOf(pendingDomainEvents); }
    public void confirmPersisted(long persistedVersion, Collection<UUID> persistedEventIds) {
        Objects.requireNonNull(persistedEventIds, "Persisted event ids must not be null.");
        if (persistedVersion < version) throw new IllegalArgumentException("Persisted version must not be lower than the current version.");
        pendingDomainEvents.removeIf(event -> persistedEventIds.contains(event.eventId())); version = persistedVersion;
    }
    public long version() { return version; }
    public UUID caseId() { return caseId; }
    public Instant createdAt() { return createdAt; }
    public Optional<QueryFingerprint> queryFingerprint() { return queryFingerprint; }
    public List<CaseEvent> events() { return List.copyOf(events); }
    public List<Evidence> evidences() { return List.copyOf(evidences); }
    public CaseStatus status() { return status; }
}
