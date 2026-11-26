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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    private Optional<Diagnosis> diagnosis = Optional.empty();
    private Optional<OptimizationHypothesis> hypothesis = Optional.empty();
    private Optional<Recommendation> recommendation = Optional.empty();
    private long recommendationVersion;
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
        result.restoreSafeWorkflowArtifacts();
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
        if (status != CaseStatus.TRIAGED && status != CaseStatus.EVIDENCE_COLLECTED) {
            throw new InvalidCaseTransitionException(status, CaseStatus.EVIDENCE_COLLECTED);
        }
        evidence.requireBelongsTo(caseId);
        if (evidences.stream().anyMatch(item -> item.id().equals(evidence.id())))
            throw new IllegalArgumentException("Evidence is already attached to this analysis case.");
        evidences.add(evidence);
        var metadata = new TransitionMetadata(actor, "Evidence collected.", occurredAt);
        if (status == CaseStatus.TRIAGED) transition(CaseStatus.EVIDENCE_COLLECTED, metadata);
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
    /** Records an evidence-grounded diagnostic derived from findings of this case. */
    public void diagnose(Diagnosis diagnosis, TransitionMetadata metadata) {
        Objects.requireNonNull(diagnosis, "Diagnosis must not be null.");
        var ownEvidenceIds = evidences.stream().map(Evidence::id).collect(java.util.stream.Collectors.toSet());
        if (!ownEvidenceIds.containsAll(diagnosis.evidenceIds())) {
            throw new IllegalArgumentException("Diagnosis references evidence from another analysis case.");
        }
        transition(CaseStatus.DIAGNOSED, new TransitionMetadata(metadata.actor(),
                "diagnosis-v1:" + encodeDiagnosis(diagnosis) + " " + metadata.reason(), metadata.occuredAt()));
        this.diagnosis = Optional.of(diagnosis);
    }
    /** Records a bounded, testable hypothesis linked to one finding of the diagnosis. */
    public void proposeHypothesis(OptimizationHypothesis hypothesis, TransitionMetadata metadata) {
        Objects.requireNonNull(hypothesis, "Optimization hypothesis must not be null.");
        var currentDiagnosis = diagnosis.orElseThrow(() ->
                new IllegalStateException("A hypothesis requires the diagnosis available in this aggregate."));
        if (!currentDiagnosis.findings().contains(hypothesis.origin())) {
            throw new IllegalArgumentException("Hypothesis origin must be a finding of the diagnosis.");
        }
        transition(CaseStatus.HYPOTHESIS_PROPOSED, new TransitionMetadata(metadata.actor(),
                "hypothesis-v1:" + encodeHypothesis(hypothesis) + " " + metadata.reason(), metadata.occuredAt()));
        this.hypothesis = Optional.of(hypothesis);
    }
    /** Starts validation only after a sandbox profile and reason are recorded. */
    public void startValidation(TransitionMetadata metadata) { transition(CaseStatus.VALIDATING, metadata); }
    /** Records one of the three mutually exclusive sandbox outcomes. */
    public void recordValidation(ValidationOutcome outcome, TransitionMetadata metadata) {
        Objects.requireNonNull(outcome, "Validation outcome must not be null.");
        transition(CaseStatus.valueOf(outcome.name()), metadata);
    }
    /** Prepares an evidence-linked recommendation for human review. */
    public void prepareRecommendation(Recommendation recommendation, TransitionMetadata metadata) {
        Objects.requireNonNull(recommendation, "Recommendation must not be null.");
        if (status != CaseStatus.VALIDATED && status != CaseStatus.INVALIDATED && status != CaseStatus.INCONCLUSIVE)
            throw new InvalidCaseTransitionException(status, CaseStatus.AWAITING_HUMAN);
        if (recommendation.version() != recommendationVersion + 1) {
            throw new IllegalArgumentException("Recommendation version must be sequential.");
        }
        if (hypothesis.isPresent() && !hypothesis.get().equals(recommendation.hypothesis())) {
            throw new IllegalArgumentException("Recommendation must use the case hypothesis.");
        }
        transition(CaseStatus.AWAITING_HUMAN, new TransitionMetadata(metadata.actor(),
                "recommendation-version=" + recommendation.version() + "; " + metadata.reason(), metadata.occuredAt()));
        this.recommendation = Optional.of(recommendation);
        this.recommendationVersion = recommendation.version();
    }
    /** Human approval is recorded only; it never executes DDL or DML. */
    public void approve(long reviewedVersion, TransitionMetadata metadata) { requireCurrentFor(CaseStatus.APPROVED); requireRecommendationVersion(reviewedVersion); transition(CaseStatus.APPROVED, metadata); }
    public void reject(long reviewedVersion, TransitionMetadata metadata) { requireCurrentFor(CaseStatus.REJECTED); requireRecommendationVersion(reviewedVersion); transition(CaseStatus.REJECTED, metadata); }
    public void requestChanges(long reviewedVersion, TransitionMetadata metadata) { requireCurrentFor(CaseStatus.CHANGES_REQUESTED); requireRecommendationVersion(reviewedVersion); transition(CaseStatus.CHANGES_REQUESTED, metadata); }
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

    private void requireRecommendationVersion(long reviewedVersion) {
        if (reviewedVersion < 1 || reviewedVersion != recommendationVersion) {
            throw new IllegalArgumentException("Human decision must reference the current recommendation version.");
        }
    }

    private void restoreSafeWorkflowArtifacts() {
        for (var event : events) {
            if (event.toStatus() == CaseStatus.DIAGNOSED && event.reason().startsWith("diagnosis-v1:")) {
                diagnosis = Optional.of(decodeDiagnosis(token(event.reason(), "diagnosis-v1:")));
            } else if (event.toStatus() == CaseStatus.HYPOTHESIS_PROPOSED
                    && event.reason().startsWith("hypothesis-v1:")) {
                hypothesis = Optional.of(decodeHypothesis(token(event.reason(), "hypothesis-v1:")));
            } else if (event.toStatus() == CaseStatus.AWAITING_HUMAN
                    && event.reason().startsWith("recommendation-version=")) {
                int end = event.reason().indexOf(';');
                if (end > 23) recommendationVersion = Long.parseLong(event.reason().substring(23, end));
            }
        }
    }

    private static String token(String reason, String prefix) {
        int end = reason.indexOf(' ', prefix.length());
        return reason.substring(prefix.length(), end < 0 ? reason.length() : end);
    }

    private static String encodeDiagnosis(Diagnosis diagnosis) {
        String value = String.join("\n", diagnosis.findings().stream().map(AnalysisCase::encodeFinding).toList());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Diagnosis decodeDiagnosis(String encoded) {
        String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        var findings = value.lines().map(AnalysisCase::decodeFinding).toList();
        var ids = new java.util.LinkedHashSet<UUID>();
        findings.forEach(item -> ids.addAll(item.evidenceIds()));
        return new Diagnosis(findings, ids);
    }

    private static String encodeHypothesis(OptimizationHypothesis hypothesis) {
        String value = hypothesis.relation().value() + "|"
                + String.join(",", hypothesis.columns().stream().map(SafeCatalogReference::value).toList()) + "|"
                + encodeFinding(hypothesis.origin());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OptimizationHypothesis decodeHypothesis(String encoded) {
        String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String[] parts = value.split("\\|", 3);
        var columns = java.util.Arrays.stream(parts[1].split(","))
                .map(SafeCatalogReference::new).toList();
        return new OptimizationHypothesis(new SafeRelationReference(parts[0]), columns, decodeFinding(parts[2]));
    }

    private static String encodeFinding(Finding finding) {
        String explanation = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(finding.explanation().getBytes(StandardCharsets.UTF_8));
        return finding.ruleCode().name() + "," + finding.severity().name() + ","
                + String.join("+", finding.evidenceIds().stream().map(UUID::toString).toList()) + "," + explanation;
    }

    private static Finding decodeFinding(String encoded) {
        String[] parts = encoded.split(",", 4);
        var ids = java.util.Arrays.stream(parts[2].split("\\+")) .map(UUID::fromString).toList();
        String explanation = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
        return new Finding(com.vigil.core.domain.enums.DiagnosticRuleCode.valueOf(parts[0]),
                com.vigil.core.domain.enums.FindingSeverity.valueOf(parts[1]), ids, explanation);
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
    public Optional<Diagnosis> diagnosis() { return diagnosis; }
    public Optional<OptimizationHypothesis> hypothesis() { return hypothesis; }
    public Optional<Recommendation> recommendation() { return recommendation; }
    public long recommendationVersion() { return recommendationVersion; }
}
