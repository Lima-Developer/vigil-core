package com.vigil.application.casework;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCaseUseCasesTest {
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATALOG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final SafeRelationReference RELATION = new SafeRelationReference("relation_orders");
    private static final SafeCatalogReference COLUMN =
            new SafeCatalogReference("column_11111111111111111111111111111111");

    @Test
    void diagnosesAndProposesAnEvidenceGroundedHypothesis() {
        var repository = new Repository();
        var analysisCase = diagnosticReadyCase(true);
        repository.save(analysisCase);
        var diagnosis = new DiagnoseCaseUseCase(repository, () -> NOW, new DiagnosticRuleEngine());

        var evaluation = diagnosis.execute(new DiagnoseCaseCommand(CASE_ID, Optional.of(impact()), "rules"));

        assertAll(
                () -> assertEquals(CaseStatus.DIAGNOSED, analysisCase.status()),
                () -> assertEquals(1, evaluation.findings().size()),
                () -> assertEquals(Set.of(PLAN_ID, CATALOG_ID), analysisCase.diagnosis().orElseThrow().evidenceIds()),
                () -> assertEquals(2, repository.saves)
        );

        var finding = evaluation.findings().getFirst();
        var hypothesis = new OptimizationHypothesis(RELATION, List.of(COLUMN), finding);
        new ProposeHypothesisUseCase(repository, () -> NOW)
                .execute(new ProposeHypothesisCommand(CASE_ID, hypothesis, "rules"));

        assertAll(
                () -> assertEquals(CaseStatus.HYPOTHESIS_PROPOSED, analysisCase.status()),
                () -> assertEquals(hypothesis, analysisCase.hypothesis().orElseThrow()),
                () -> assertEquals(3, repository.saves)
        );
    }

    @Test
    void inconclusiveEvaluationDoesNotMutateOrPersistTheCaseAgain() {
        var repository = new Repository();
        var analysisCase = diagnosticReadyCase(true);
        repository.save(analysisCase);
        var useCase = new DiagnoseCaseUseCase(repository, () -> NOW, new DiagnosticRuleEngine());

        var result = useCase.execute(new DiagnoseCaseCommand(CASE_ID, Optional.empty(), "rules"));

        assertAll(
                () -> assertTrue(result.findings().isEmpty()),
                () -> assertEquals(List.of(DiagnosticInconclusiveReason.IMPACT_NOT_SELECTED),
                        result.inconclusiveReasons()),
                () -> assertEquals(CaseStatus.SANITIZED, analysisCase.status()),
                () -> assertEquals(1, repository.saves)
        );
    }

    @Test
    void missingPlanAndInvalidCommandsFailClosed() {
        var repository = new Repository();
        repository.save(diagnosticReadyCase(false));
        var useCase = new DiagnoseCaseUseCase(repository, () -> NOW, new DiagnosticRuleEngine());

        assertEquals("PLAN_EVIDENCE_MISSING", assertThrows(IllegalStateException.class,
                () -> useCase.execute(new DiagnoseCaseCommand(CASE_ID, Optional.of(impact()), "rules"))).getMessage());
        assertThrows(NullPointerException.class, () -> new DiagnoseCaseCommand(null, Optional.empty(), "rules"));
        assertThrows(NullPointerException.class, () -> new DiagnoseCaseCommand(CASE_ID, null, "rules"));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnoseCaseCommand(CASE_ID, Optional.empty(), " "));

        var finding = new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                List.of(PLAN_ID), "Explicação segura e pseudonimizada.");
        var hypothesis = new OptimizationHypothesis(RELATION, List.of(COLUMN), finding);
        assertThrows(NullPointerException.class, () -> new ProposeHypothesisCommand(null, hypothesis, "rules"));
        assertThrows(NullPointerException.class, () -> new ProposeHypothesisCommand(CASE_ID, null, "rules"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposeHypothesisCommand(CASE_ID, hypothesis, ""));
    }

    private static AnalysisCase diagnosticReadyCase(boolean includePlan) {
        var result = AnalysisCase.discover(CASE_ID, NOW);
        var metadata = new TransitionMetadata("test", "motivo seguro", NOW);
        result.triage(metadata);
        if (includePlan) {
            var node = new PlanNodeEvidence(PlanNodeType.SEQUENTIAL_SCAN, Optional.of(RELATION), true,
                    List.of(COLUMN), true, 100);
            var payload = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 100,
                    Optional.of(BigDecimal.ONE), List.of(node));
            result.collectEvidence(new Evidence(PLAN_ID, CASE_ID, payload.type(), EvidenceSource.POSTGRESQL_EXPLAIN,
                    NOW, "test", EvidenceConfidence.HIGH, payload, Optional.empty()), "test", NOW);
        } else {
            var metric = new MetricEvidencePayload(MetricKind.CALLS, BigDecimal.ONE,
                    MeasurementUnit.EXECUTIONS, new ObservationWindow(NOW.minusSeconds(60), NOW));
            result.collectEvidence(new Evidence(PLAN_ID, CASE_ID, metric.type(), EvidenceSource.PG_STAT_STATEMENTS,
                    NOW, "test", EvidenceConfidence.HIGH, metric, Optional.empty()), "test", NOW);
        }
        var blueprint = new RelationBlueprint(RELATION,
                new SafeCatalogReference("schema_22222222222222222222222222222222"), RelationType.TABLE,
                OptionalLong.of(100_000), 1_000, List.of(), List.of(), List.of());
        var catalog = RelationMetadataEvidencePayload.fromBlueprint(blueprint);
        result.collectEvidence(new Evidence(CATALOG_ID, CASE_ID, catalog.type(), EvidenceSource.POSTGRESQL_CATALOG,
                NOW, "test", EvidenceConfidence.HIGH, catalog, Optional.empty()), "test", NOW);
        result.sanitize(new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                metadata);
        return result;
    }

    private static ImpactAssessment impact() {
        return new ImpactAssessment(new QueryImpactDelta(10, new BigDecimal("1000"), 100, 10, 2,
                new BigDecimal("0.80")), 1, BaselineConfidence.ABSENT);
    }

    private static final class Repository implements AnalysisCaseRepository {
        private final Map<UUID, AnalysisCase> cases = new HashMap<>();
        private int saves;

        @Override
        public void save(AnalysisCase analysisCase) {
            cases.put(analysisCase.caseId(), analysisCase);
            saves++;
        }

        @Override
        public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            save(analysisCase);
        }

        @Override
        public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public AnalysisCase getById(UUID caseId) {
            var result = cases.get(caseId);
            if (result == null) throw new CaseNotFound(caseId);
            return result;
        }
    }
}
