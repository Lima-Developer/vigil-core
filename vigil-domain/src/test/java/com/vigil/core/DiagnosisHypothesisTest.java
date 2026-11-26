package com.vigil.core;

import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosisHypothesisTest {
    private static final UUID CASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void diagnoseRejectsMissingFindingAndEvidenceFromAnotherCase() {
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis(List.of(), Set.of()));
        var analysisCase = sanitizedCase();
        var foreign = UUID.fromString("99999999-9999-9999-9999-999999999999");
        var finding = finding(foreign);
        var diagnosis = new Diagnosis(List.of(finding), Set.of(foreign));

        assertThrows(IllegalArgumentException.class, () -> analysisCase.diagnose(diagnosis, metadata()));
        assertEquals(CaseStatus.SANITIZED, analysisCase.status());
    }

    @Test
    void hypothesisMustBeLinkedToAFindingOfTheRecordedDiagnosis() {
        var analysisCase = sanitizedCase();
        var ownEvidence = analysisCase.evidences().getFirst().id();
        var diagnosisFinding = finding(ownEvidence);
        analysisCase.diagnose(new Diagnosis(List.of(diagnosisFinding), Set.of(ownEvidence)), metadata());
        var unrelated = new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.WARNING,
                List.of(ownEvidence), "Outra explicação segura e pseudonimizada.");
        var hypothesis = new OptimizationHypothesis(new SafeRelationReference("relation_orders"),
                List.of(new SafeCatalogReference("column_11111111111111111111111111111111")), unrelated);

        assertThrows(IllegalArgumentException.class, () -> analysisCase.proposeHypothesis(hypothesis, metadata()));
        assertEquals(CaseStatus.DIAGNOSED, analysisCase.status());
    }

    @Test
    void recommendationRequiresTechnicalMaterialAndDecisionRequiresItsCurrentVersion() {
        var analysisCase = sanitizedCase();
        var evidenceId = analysisCase.evidences().getFirst().id();
        var finding = finding(evidenceId);
        var hypothesis = new OptimizationHypothesis(new SafeRelationReference("relation_orders"),
                List.of(new SafeCatalogReference("column_11111111111111111111111111111111")), finding);
        analysisCase.diagnose(new Diagnosis(List.of(finding), Set.of(evidenceId)), metadata());
        analysisCase.proposeHypothesis(hypothesis, metadata());
        analysisCase.startValidation(metadata());
        analysisCase.recordValidation(ValidationOutcome.VALIDATED, metadata());
        assertThrows(IllegalArgumentException.class, () -> new Recommendation(1, hypothesis,
                ValidationOutcome.VALIDATED, Set.of(evidenceId), List.of(), List.of()));
        var recommendation = new Recommendation(1, hypothesis, ValidationOutcome.VALIDATED, Set.of(evidenceId),
                List.of(RecommendationRisk.PLAN_VARIABILITY), List.of(RecommendationLimitation.SYNTHETIC_DATA_ONLY));
        analysisCase.prepareRecommendation(recommendation, metadata());

        assertThrows(IllegalArgumentException.class, () -> analysisCase.approve(2, metadata()));
        analysisCase.approve(1, metadata());
        assertEquals(CaseStatus.APPROVED, analysisCase.status());
    }

    private static AnalysisCase sanitizedCase() {
        var result = AnalysisCase.discover(CASE, NOW);
        result.triage(metadata());
        var payload = new MetricEvidencePayload(MetricKind.CALLS, BigDecimal.ONE, MeasurementUnit.EXECUTIONS,
                new ObservationWindow(NOW.minusSeconds(60), NOW));
        result.collectEvidence(new Evidence(UUID.fromString("22222222-2222-2222-2222-222222222222"), CASE,
                payload.type(), EvidenceSource.PG_STAT_STATEMENTS, NOW, "test", EvidenceConfidence.HIGH,
                payload, Optional.empty()), "test", NOW);
        result.sanitize(new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                metadata());
        return result;
    }

    private static Finding finding(UUID evidenceId) {
        return new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                List.of(evidenceId), "Explicação segura e pseudonimizada.");
    }

    private static TransitionMetadata metadata() {
        return new TransitionMetadata("test", "motivo seguro", NOW);
    }
}
