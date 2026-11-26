package com.vigil.core;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.Diagnosis;
import com.vigil.core.domain.Finding;
import com.vigil.core.domain.OptimizationHypothesis;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.Recommendation;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.enums.ValidationOutcome;
import com.vigil.core.domain.enums.DiagnosticRuleCode;
import com.vigil.core.domain.enums.FindingSeverity;
import com.vigil.core.exception.InvalidCaseTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisCaseLifecycleTest {
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant AT = Instant.parse("2026-11-17T20:00:00Z");
    private static final SanitizationResult.Safe SAFE =
            new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED));

    @Test
    void shouldTraverseTheWholeAuditableLifecycleWithoutExecutingAnyTargetChange() {
        var analysisCase = AnalysisCase.discover(CASE_ID, AT);
        analysisCase.triage(meta("accepted for investigation", 1));
        analysisCase.collectEvidence(evidence(), "collector", AT.plusSeconds(2));
        analysisCase.sanitize(SAFE, meta("safe context produced", 3));
        diagnoseAndPropose(analysisCase, 4);
        analysisCase.startValidation(meta("sandbox profile synthetic-only", 6));
        analysisCase.recordValidation(ValidationOutcome.VALIDATED, meta("estimated benefit accepted", 7));
        analysisCase.prepareRecommendation(recommendation(analysisCase, ValidationOutcome.VALIDATED),
                meta("recommendation includes limits", 8));
        analysisCase.approve(1, meta("human reviewer approves recommendation", 9));
        analysisCase.archive(meta("closed without automatic execution", 10));

        assertAll(
                () -> assertEquals(CaseStatus.ARCHIVED, analysisCase.status()),
                () -> assertEquals(10, analysisCase.events().size()),
                () -> assertEquals(CaseStatus.DISCOVERED, analysisCase.events().getFirst().fromStatus()),
                () -> assertEquals(CaseStatus.ARCHIVED, analysisCase.events().getLast().toStatus()),
                () -> assertThrows(InvalidCaseTransitionException.class, () -> analysisCase.triage(meta("must remain closed", 11)))
        );
    }

    @Test
    void shouldAllowHumanRequestedChangesToReopenOnlyThroughRetriage() {
        var analysisCase = readyForHumanReview(ValidationOutcome.INCONCLUSIVE);
        analysisCase.requestChanges(1, meta("need a wider synthetic sample", 20));

        assertAll(
                () -> assertEquals(CaseStatus.CHANGES_REQUESTED, analysisCase.status()),
                () -> assertThrows(InvalidCaseTransitionException.class, () ->
                        analysisCase.diagnose(diagnosis(analysisCase.evidences().getFirst()), meta("cannot skip retriage", 21)))
        );
        analysisCase.retriage(meta("review request accepted", 22));
        assertEquals(CaseStatus.TRIAGED, analysisCase.status());
    }

    @Test
    void shouldRejectHumanDecisionBeforeRecommendationWithoutChangingHistory() {
        var analysisCase = AnalysisCase.discover(CASE_ID, AT);
        assertThrows(InvalidCaseTransitionException.class, () -> analysisCase.approve(1, meta("too early", 30)));
        assertAll(() -> assertEquals(CaseStatus.DISCOVERED, analysisCase.status()),
                () -> assertTrue(analysisCase.events().isEmpty()));
    }

    private static AnalysisCase readyForHumanReview(ValidationOutcome outcome) {
        var analysisCase = AnalysisCase.discover(CASE_ID, AT);
        analysisCase.triage(meta("triage", 1));
        analysisCase.collectEvidence(evidence(), "collector", AT.plusSeconds(2));
        analysisCase.sanitize(SAFE, meta("sanitized", 3));
        diagnoseAndPropose(analysisCase, 4);
        analysisCase.startValidation(meta("sandbox", 6));
        analysisCase.recordValidation(outcome, meta("outcome", 7));
        analysisCase.prepareRecommendation(recommendation(analysisCase, outcome), meta("recommendation", 8));
        return analysisCase;
    }

    private static TransitionMetadata meta(String reason, long seconds) {
        return new TransitionMetadata("human-reviewer", reason, AT.plusSeconds(seconds));
    }

    private static Evidence evidence() {
        return new Evidence(UUID.randomUUID(), CASE_ID, EvidenceType.METRIC, EvidenceSource.PG_STAT_STATEMENTS,
                AT, "test", EvidenceConfidence.HIGH,
                new MetricEvidencePayload(MetricKind.CALLS, BigDecimal.ONE, MeasurementUnit.EXECUTIONS,
                        new ObservationWindow(AT.minusSeconds(60), AT)), Optional.empty());
    }

    private static void diagnoseAndPropose(AnalysisCase analysisCase, long second) {
        var diagnosis = diagnosis(analysisCase.evidences().getFirst());
        analysisCase.diagnose(diagnosis, meta("diagnosed", second));
        analysisCase.proposeHypothesis(new OptimizationHypothesis(
                new SafeRelationReference("relation_orders"),
                List.of(new SafeCatalogReference("column_0123456789abcdef0123456789abcdef")),
                diagnosis.findings().getFirst()), meta("hypothesis", second + 1));
    }

    private static Diagnosis diagnosis(Evidence evidence) {
        var finding = new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                List.of(evidence.id()), "Explicação segura baseada exclusivamente em evidência pseudonimizada.");
        return new Diagnosis(List.of(finding), java.util.Set.of(evidence.id()));
    }

    private static Recommendation recommendation(AnalysisCase analysisCase, ValidationOutcome outcome) {
        var hypothesis = analysisCase.hypothesis().orElseThrow();
        return new Recommendation(1, hypothesis, outcome, java.util.Set.copyOf(hypothesis.origin().evidenceIds()),
                List.of(com.vigil.core.domain.enums.RecommendationRisk.PLAN_VARIABILITY),
                List.of(com.vigil.core.domain.enums.RecommendationLimitation.SYNTHETIC_DATA_ONLY));
    }
}
