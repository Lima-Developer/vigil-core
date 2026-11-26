package com.vigil.application.casework;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.AnalysisCaseDiscovered;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.QueryTriaged;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.Diagnosis;
import com.vigil.core.domain.Finding;
import com.vigil.core.domain.OptimizationHypothesis;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.enums.TelemetrySource;
import com.vigil.core.domain.enums.ValidationOutcome;
import com.vigil.core.domain.enums.QueryShape;
import com.vigil.core.domain.enums.DiagnosticRuleCode;
import com.vigil.core.domain.enums.FindingSeverity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisCaseUseCasesTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID NEW_CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXISTING_CASE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void shouldCreateAndPersistACaseForTheObservedQuery() {
        var repository = new FakeAnalysisCaseRepository();
        var idGenerator = new FixedIdGenerator(NEW_CASE_ID, EVENT_ID);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), idGenerator);

        var result = useCase.execute(discoveryCommand("observation-1"));
        var savedCase = repository.getById(NEW_CASE_ID);

        assertAll(
                () -> assertEquals(NEW_CASE_ID, result.caseId()),
                () -> assertTrue(result.created()),
                () -> assertEquals(NOW, savedCase.createdAt()),
                () -> assertEquals(observedQuery().fingerprint(), savedCase.queryFingerprint().orElseThrow()),
                () -> assertEquals(2, idGenerator.calls()),
                () -> assertEquals(
                        List.of(new AnalysisCaseDiscovered(EVENT_ID, NEW_CASE_ID, "vigil-system", NOW, "1")),
                        savedCase.pendingEvents())
        );
    }

    @Test
    void shouldReturnTheExistingCaseWhenDiscoveryIsRetriedWithTheSameKey() {
        var repository = new FakeAnalysisCaseRepository();
        var idGenerator = new FixedIdGenerator(NEW_CASE_ID, EVENT_ID);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), idGenerator);

        var first = useCase.execute(discoveryCommand("observation-1"));
        var retry = useCase.execute(discoveryCommand("observation-1"));

        assertAll(
                () -> assertTrue(first.created()),
                () -> assertEquals(first.caseId(), retry.caseId()),
                () -> assertFalse(retry.created()),
                () -> assertEquals(1, repository.discoveredSaves()),
                () -> assertEquals(2, idGenerator.calls())
        );
    }

    @Test
    void shouldReturnTheConcurrentWinnerWhenDiscoveryConflicts() {
        var repository = new FakeAnalysisCaseRepository();
        var winner = AnalysisCase.discover(EXISTING_CASE_ID, NOW);
        repository.failNextDiscoveryWithWinner("observation-1", winner);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), new FixedIdGenerator(NEW_CASE_ID, EVENT_ID));

        var result = useCase.execute(discoveryCommand("observation-1"));

        assertAll(
                () -> assertEquals(EXISTING_CASE_ID, result.caseId()),
                () -> assertFalse(result.created()),
                () -> assertEquals(0, repository.discoveredSaves())
        );
    }

    @Test
    void shouldPropagateAConflictWhenNoConcurrentWinnerCanBeRead() {
        var repository = new FakeAnalysisCaseRepository();
        repository.failNextDiscoveryWithoutWinner();
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), new FixedIdGenerator(NEW_CASE_ID, EVENT_ID));

        assertThrows(ConcurrentCaseUpdate.class, () -> useCase.execute(discoveryCommand("observation-1")));
    }

    @Test
    void shouldTriageAnExistingDiscoveredCaseAndPersistIt() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        repository.save(analysisCase);
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"));

        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(2, repository.saves()),
                () -> assertEquals(NOW, analysisCase.events().getFirst().occuredAt()),
                () -> assertEquals(
                        List.of(new QueryTriaged(EVENT_ID, NEW_CASE_ID, "operator", NOW, "1")),
                        analysisCase.pendingEvents())
        );
    }

    @Test
    void shouldExposeNotFoundWhenTriagingAnUnknownCase() {
        var repository = new FakeAnalysisCaseRepository();
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        assertThrows(
                CaseNotFound.class,
                () -> useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"))
        );
        assertEquals(0, repository.saves());
    }

    @Test
    void shouldMapInvalidDomainTransitionsWithoutPersistingTheCase() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        analysisCase.triage("Already triaged.", "operator", NOW);
        repository.save(analysisCase);
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        var exception = assertThrows(
                InvalidAnalysisCaseTransition.class,
                () -> useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"))
        );

        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(1, repository.saves()),
                () -> assertTrue(exception.getCause() instanceof com.vigil.core.exception.InvalidCaseTransitionException)
        );
    }

    @Test
    void shouldRecordAnAuditableHumanDecisionOnlyAfterHumanReviewIsReady() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = awaitingHumanReviewCase();
        repository.save(analysisCase);
        var useCase = new RecordHumanDecisionUseCase(repository, fixedClock());

        useCase.execute(new RecordHumanDecisionCommand(NEW_CASE_ID, HumanDecision.APPROVE,
                "Evidências sintéticas suficientes para aprovar a recomendação.", "reviewer", 1));

        assertAll(
                () -> assertEquals(CaseStatus.APPROVED, analysisCase.status()),
                () -> assertEquals("reviewer", analysisCase.events().getLast().actor()),
                () -> assertEquals(2, repository.saves())
        );
    }

    @Test
    void shouldRejectHumanDecisionBeforeHumanReviewWithoutPersisting() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        repository.save(analysisCase);
        var useCase = new RecordHumanDecisionUseCase(repository, fixedClock());

        assertThrows(InvalidAnalysisCaseTransition.class, () -> useCase.execute(
                new RecordHumanDecisionCommand(NEW_CASE_ID, HumanDecision.REJECT,
                        "Ainda não existe recomendação a revisar.", "reviewer", 1)));
        assertEquals(1, repository.saves());
    }

    @Test
    void shouldChainAutomaticClassificationBeforeHumanReview() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = validatingCase();
        repository.save(analysisCase);
        HypothesisValidationPort validator = ignored -> new HypothesisValidationPort.ValidationAssessment(
                ValidationOutcome.VALIDATED, "Índice parcial reduziu o tempo no laboratório isolado.");
        var useCase = new ValidateAnalysisCaseUseCase(repository, validator, fixedClock());

        var result = useCase.execute(new ValidateAnalysisCaseCommand(NEW_CASE_ID, "vigil-system"));

        assertAll(
                () -> assertEquals(ValidationOutcome.VALIDATED, result.outcome()),
                () -> assertEquals(CaseStatus.AWAITING_HUMAN, analysisCase.status()),
                () -> assertEquals(2, repository.saves()),
                () -> assertEquals(CaseStatus.AWAITING_HUMAN, analysisCase.events().getLast().toStatus())
        );
    }

    @Test
    void shouldRejectUnsupportedQueryShapeBeforeCallingTheValidator() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = validatingCase();
        repository.save(analysisCase);
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        HypothesisValidationPort validator = ignored -> {
            called.set(true);
            return new HypothesisValidationPort.ValidationAssessment(ValidationOutcome.VALIDATED, "não deve executar");
        };
        var useCase = new ValidateAnalysisCaseUseCase(repository, validator, fixedClock());

        assertThrows(IllegalArgumentException.class, () -> useCase.execute(
                new ValidateAnalysisCaseCommand(NEW_CASE_ID, "system", QueryShape.SUBQUERY)));
        assertFalse(called.get());
        assertEquals(CaseStatus.VALIDATING, analysisCase.status());
    }

    private static DiscoverObservedQueryCommand discoveryCommand(String idempotencyKey) {
        return new DiscoverObservedQueryCommand(observedQuery(), idempotencyKey);
    }

    private static ObservedQuery observedQuery() {
        return new ObservedQuery(
                new QueryFingerprint(
                        "hmac-sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "v1"),
                TelemetrySource.PG_STAT_STATEMENTS,
                NOW,
                Optional.empty(),
                CommandType.SELECT,
                new ObservationWindow(NOW.minusSeconds(60), NOW),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 100)
        );
    }

    private static Clock fixedClock() {
        return () -> NOW;
    }

    private static AnalysisCase awaitingHumanReviewCase() {
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        var metadata = new TransitionMetadata("system", "Laboratório sintético", NOW);
        analysisCase.triage(metadata);
        var payload = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 100,
                Optional.of(BigDecimal.TEN));
        var evidence = new Evidence(UUID.randomUUID(), NEW_CASE_ID, payload.type(),
                EvidenceSource.POSTGRESQL_EXPLAIN, NOW, "test", EvidenceConfidence.HIGH, payload, Optional.empty());
        analysisCase.collectEvidence(evidence, "system", NOW);
        analysisCase.sanitize(
                new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)), metadata);
        diagnoseAndPropose(analysisCase, evidence, metadata);
        analysisCase.startValidation(metadata);
        analysisCase.recordValidation(ValidationOutcome.VALIDATED, metadata);
        var hypothesis = analysisCase.hypothesis().orElseThrow();
        analysisCase.prepareRecommendation(new com.vigil.core.domain.Recommendation(1, hypothesis,
                ValidationOutcome.VALIDATED, java.util.Set.copyOf(hypothesis.origin().evidenceIds()),
                List.of(com.vigil.core.domain.enums.RecommendationRisk.PLAN_VARIABILITY),
                List.of(com.vigil.core.domain.enums.RecommendationLimitation.SYNTHETIC_DATA_ONLY)), metadata);
        return analysisCase;
    }

    private static AnalysisCase validatingCase() {
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        var metadata = new TransitionMetadata("system", "Laboratório sintético", NOW);
        analysisCase.triage(metadata);
        var payload = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 100,
                Optional.of(BigDecimal.TEN));
        var evidence = new Evidence(UUID.randomUUID(), NEW_CASE_ID, payload.type(),
                EvidenceSource.POSTGRESQL_EXPLAIN, NOW, "test", EvidenceConfidence.HIGH, payload, Optional.empty());
        analysisCase.collectEvidence(evidence, "system", NOW);
        analysisCase.sanitize(
                new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)), metadata);
        diagnoseAndPropose(analysisCase, evidence, metadata);
        analysisCase.startValidation(metadata);
        return analysisCase;
    }

    private static void diagnoseAndPropose(AnalysisCase analysisCase, Evidence evidence, TransitionMetadata metadata) {
        var finding = new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                List.of(evidence.id()), "Explicação segura baseada exclusivamente em evidência pseudonimizada.");
        analysisCase.diagnose(new Diagnosis(List.of(finding), java.util.Set.of(evidence.id())), metadata);
        analysisCase.proposeHypothesis(new OptimizationHypothesis(
                new SafeRelationReference("relation_orders"),
                List.of(new SafeCatalogReference("column_0123456789abcdef0123456789abcdef")), finding), metadata);
    }

    private static final class FixedIdGenerator implements IdGenerator {
        private final UUID[] ids;
        private int calls;

        private FixedIdGenerator(UUID... ids) {
            this.ids = ids;
        }

        @Override
        public UUID nextId() {
            return ids[calls++];
        }

        private int calls() {
            return calls;
        }
    }

    private static final class FakeAnalysisCaseRepository implements AnalysisCaseRepository {
        private final Map<UUID, AnalysisCase> cases = new HashMap<>();
        private final Map<String, AnalysisCase> discoveries = new HashMap<>();
        private String conflictingKey;
        private AnalysisCase winnerAfterConflict;
        private boolean conflictWithoutWinner;
        private int saves;
        private int discoveredSaves;

        @Override
        public void save(AnalysisCase analysisCase) {
            cases.put(analysisCase.caseId(), analysisCase);
            saves++;
        }

        @Override
        public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            if (conflictWithoutWinner || idempotencyKey.equals(conflictingKey)) {
                if (winnerAfterConflict != null) {
                    cases.put(winnerAfterConflict.caseId(), winnerAfterConflict);
                    discoveries.put(idempotencyKey, winnerAfterConflict);
                }
                conflictWithoutWinner = false;
                conflictingKey = null;
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
            if (discoveries.containsKey(idempotencyKey)) {
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
            cases.put(analysisCase.caseId(), analysisCase);
            discoveries.put(idempotencyKey, analysisCase);
            discoveredSaves++;
        }

        @Override
        public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(discoveries.get(idempotencyKey));
        }

        @Override
        public AnalysisCase getById(UUID caseId) {
            var analysisCase = cases.get(caseId);
            if (analysisCase == null) {
                throw new CaseNotFound(caseId);
            }
            return analysisCase;
        }

        private void failNextDiscoveryWithWinner(String idempotencyKey, AnalysisCase winner) {
            conflictingKey = idempotencyKey;
            winnerAfterConflict = winner;
        }

        private void failNextDiscoveryWithoutWinner() {
            conflictWithoutWinner = true;
        }

        private int saves() {
            return saves;
        }

        private int discoveredSaves() {
            return discoveredSaves;
        }
    }
}
