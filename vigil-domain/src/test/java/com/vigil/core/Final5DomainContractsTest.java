package com.vigil.core;

import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import com.vigil.core.exception.InvalidCaseTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Final5DomainContractsTest {
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVIDENCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final SafeRelationReference RELATION = new SafeRelationReference("relation_orders");
    private static final SafeCatalogReference COLUMN =
            new SafeCatalogReference("column_11111111111111111111111111111111");

    @Test
    void queryShapeAndFidelityContractsAreExplicit() {
        assertAll(
                () -> assertTrue(QueryShape.SIMPLE_SELECT.automaticValidationSupported()),
                () -> assertTrue(QueryShape.JOIN.automaticValidationSupported()),
                () -> assertTrue(QueryShape.AGGREGATE.automaticValidationSupported()),
                () -> assertFalse(QueryShape.SUBQUERY.automaticValidationSupported()),
                () -> assertFalse(QueryShape.SET_OPERATION.automaticValidationSupported()),
                () -> assertFalse(QueryShape.UNSUPPORTED.automaticValidationSupported()),
                () -> assertEquals(3, FidelityLevel.values().length),
                () -> assertEquals(FidelityLevel.HYPOTHETICAL_INDEX,
                        FidelityLevel.valueOf("HYPOTHETICAL_INDEX"))
        );
    }

    @Test
    void relationMetadataRejectsInvalidOrOverbroadBlueprints() {
        var schema = new SafeCatalogReference("schema_22222222222222222222222222222222");
        var valid = new RelationBlueprint(RELATION, schema, RelationType.TABLE, OptionalLong.of(20_000),
                100, List.of(), List.of(), List.of());
        var payload = RelationMetadataEvidencePayload.fromBlueprint(valid);

        assertAll(
                () -> assertEquals(20_000, payload.estimatedRows()),
                () -> assertEquals(EvidenceType.RELATION_METADATA, payload.type()),
                () -> assertTrue(payload.blueprint().isPresent()),
                () -> assertTrue(new RelationMetadataEvidencePayload(RELATION, 1, 0, false)
                        .blueprint().isEmpty()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RelationMetadataEvidencePayload(RELATION, -1, 0, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RelationMetadataEvidencePayload(RELATION, 1, -1, false)),
                () -> assertThrows(NullPointerException.class,
                        () -> new RelationMetadataEvidencePayload(RELATION, 1, 0, false, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> RelationMetadataEvidencePayload.fromBlueprint(null))
        );

        var other = new RelationBlueprint(new SafeRelationReference("relation_other"), schema, RelationType.TABLE,
                OptionalLong.of(1), 1, List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new RelationMetadataEvidencePayload(RELATION, 1, 0, false, Optional.of(other)));
    }

    @Test
    void ruleEngineReportsEveryFailClosedEvidenceGap() {
        var engine = new DiagnosticRuleEngine();
        var legacy = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 1,
                Optional.of(BigDecimal.ONE));
        assertEquals(List.of(DiagnosticInconclusiveReason.LEGACY_PLAN_WITHOUT_NODE_FACTS),
                engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, legacy), List.of(),
                        Optional.of(impact())).inconclusiveReasons());

        var withoutRelation = plan(new PlanNodeEvidence(PlanNodeType.SEQUENTIAL_SCAN, Optional.empty(), true,
                List.of(COLUMN), true, 1));
        assertEquals(List.of(DiagnosticInconclusiveReason.RELATION_NOT_CORRELATED),
                engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, withoutRelation), List.of(),
                        Optional.of(impact())).inconclusiveReasons());

        var correlated = plan(new PlanNodeEvidence(PlanNodeType.SEQUENTIAL_SCAN, Optional.of(RELATION), true,
                List.of(COLUMN), true, 1));
        assertEquals(List.of(DiagnosticInconclusiveReason.RELATION_NOT_CORRELATED),
                engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, correlated), List.of(),
                        Optional.of(impact())).inconclusiveReasons());

        var unknownSize = relation(OptionalLong.empty());
        assertEquals(List.of(DiagnosticInconclusiveReason.RELATION_SIZE_UNKNOWN),
                engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, correlated),
                        List.of(new DiagnosticRuleEngine.CatalogEvidence(UUID.randomUUID(), unknownSize)),
                        Optional.of(impact())).inconclusiveReasons());

        var nonSelective = plan(new PlanNodeEvidence(PlanNodeType.SEQUENTIAL_SCAN, Optional.of(RELATION), true,
                List.of(COLUMN), true, 19_000));
        assertTrue(engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, nonSelective),
                List.of(new DiagnosticRuleEngine.CatalogEvidence(UUID.randomUUID(), relation(OptionalLong.of(20_000)))),
                Optional.of(impact())).findings().isEmpty());

        var ignored = plan(new PlanNodeEvidence(PlanNodeType.INDEX_SCAN, Optional.of(RELATION), false,
                List.of(), true, 1));
        assertTrue(engine.evaluate(new DiagnosticRuleEngine.PlanEvidence(EVIDENCE_ID, ignored), List.of(),
                Optional.of(impact())).findings().isEmpty());
        assertThrows(NullPointerException.class, () -> new DiagnosticRuleEngine(null));
        assertThrows(NullPointerException.class,
                () -> new DiagnosticRuleEngine.PlanEvidence(null, legacy));
        assertThrows(NullPointerException.class,
                () -> new DiagnosticRuleEngine.CatalogEvidence(null, unknownSize));
    }

    @Test
    void workflowArtifactsAndRecommendationVersionSurviveReconstitution() {
        var original = awaitingHumanCase();
        var restored = AnalysisCase.reconstitute(original.caseId(), original.createdAt(), Optional.empty(),
                original.status(), 7, original.events(), original.evidences());

        assertAll(
                () -> assertTrue(restored.diagnosis().isPresent()),
                () -> assertTrue(restored.hypothesis().isPresent()),
                () -> assertEquals(1, restored.recommendationVersion()),
                () -> assertTrue(restored.recommendation().isEmpty())
        );
        restored.requestChanges(1, metadata());
        restored.retriage(metadata());
        restored.archive(metadata());
        assertEquals(CaseStatus.ARCHIVED, restored.status());

        var rejected = awaitingHumanCase();
        rejected.reject(1, metadata());
        rejected.archive(metadata());
        assertEquals(CaseStatus.ARCHIVED, rejected.status());
    }

    @Test
    void evidenceAttachmentIsUniqueAndOnlyAllowedInItsLifecycleStage() {
        var analysisCase = AnalysisCase.discover(CASE_ID, NOW);
        assertThrows(InvalidCaseTransitionException.class,
                () -> analysisCase.collectEvidence(evidence(), "test", NOW));
        analysisCase.triage(metadata());
        analysisCase.collectEvidence(evidence(), "test", NOW);
        assertThrows(IllegalArgumentException.class,
                () -> analysisCase.collectEvidence(evidence(), "test", NOW));

        var archived = AnalysisCase.discover(UUID.randomUUID(), NOW);
        archived.archive(metadata());
        assertThrows(InvalidCaseTransitionException.class, () -> archived.archive(metadata()));
    }

    private static AnalysisCase awaitingHumanCase() {
        var result = AnalysisCase.discover(CASE_ID, NOW);
        result.triage(metadata());
        result.collectEvidence(evidence(), "test", NOW);
        result.sanitize(new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                metadata());
        var finding = new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                List.of(EVIDENCE_ID), "Explicação segura e pseudonimizada.");
        var diagnosis = new Diagnosis(List.of(finding), Set.of(EVIDENCE_ID));
        var hypothesis = new OptimizationHypothesis(RELATION, List.of(COLUMN), finding);
        result.diagnose(diagnosis, metadata());
        result.proposeHypothesis(hypothesis, metadata());
        result.startValidation(metadata());
        result.recordValidation(ValidationOutcome.VALIDATED, metadata());
        result.prepareRecommendation(new Recommendation(1, hypothesis, ValidationOutcome.VALIDATED,
                Set.of(EVIDENCE_ID), List.of(RecommendationRisk.PLAN_VARIABILITY),
                List.of(RecommendationLimitation.SYNTHETIC_DATA_ONLY)), metadata());
        return result;
    }

    private static Evidence evidence() {
        var payload = new MetricEvidencePayload(MetricKind.CALLS, BigDecimal.ONE, MeasurementUnit.EXECUTIONS,
                new ObservationWindow(NOW.minusSeconds(60), NOW));
        return new Evidence(EVIDENCE_ID, CASE_ID, payload.type(), EvidenceSource.PG_STAT_STATEMENTS, NOW,
                "test", EvidenceConfidence.HIGH, payload, Optional.empty());
    }

    private static PlanSummaryEvidencePayload plan(PlanNodeEvidence node) {
        return new PlanSummaryEvidencePayload(List.of(node.type()), node.estimatedRows(),
                Optional.of(BigDecimal.ONE), List.of(node));
    }

    private static RelationBlueprint relation(OptionalLong rows) {
        return new RelationBlueprint(RELATION,
                new SafeCatalogReference("schema_22222222222222222222222222222222"), RelationType.TABLE,
                rows, 100, List.of(), List.of(), List.of());
    }

    private static ImpactAssessment impact() {
        return new ImpactAssessment(new QueryImpactDelta(10, new BigDecimal("1000"), 100, 10, 2,
                new BigDecimal("0.80")), 1, BaselineConfidence.ABSENT);
    }

    private static TransitionMetadata metadata() {
        return new TransitionMetadata("test", "motivo seguro", NOW);
    }
}
