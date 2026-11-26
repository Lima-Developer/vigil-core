package com.vigil.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vigil.core.domain.BaselineConfidence;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.FamilyImpact;
import com.vigil.core.domain.ImpactAssessment;
import com.vigil.core.domain.ImpactSelectionPolicy;
import com.vigil.core.domain.ImpactSelectionResult;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.QueryImpactDelta;
import com.vigil.core.domain.SelectionReason;
import com.vigil.core.domain.SourceQueryId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the validation invariants and edge cases of the impact-selection domain types. */
class ImpactDomainValidationTest {
    private static final QueryFingerprint FP = new QueryFingerprint("hmac-sha256:" + "a".repeat(64), "v1");
    private static final List<SourceQueryId> ONE_SOURCE = List.of(new SourceQueryId("1"));

    @Test
    void policyConstructorRejectsInvalidThresholds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionPolicy(new BigDecimal("-0.1"), BigDecimal.TEN, 5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionPolicy(new BigDecimal("1.1"), BigDecimal.TEN, 5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionPolicy(new BigDecimal("0.05"), new BigDecimal("-1"), 5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionPolicy(new BigDecimal("0.05"), BigDecimal.TEN, 0)));
    }

    @Test
    void zeroWorkspaceTotalYieldsNoCandidates() {
        var result = new ImpactSelectionPolicy().select(
                List.of(new FamilyImpact(FP, 10, new BigDecimal("500"), 10, 0, 0, ONE_SOURCE)),
                BigDecimal.ZERO, 0);

        assertEquals(ImpactSelectionResult.Outcome.NO_CANDIDATES_ABOVE_THRESHOLDS, result.outcome());
    }

    @Test
    void shareIsClampedToOneWhenFamilyExceedsTheWorkspaceTotal() {
        // Degenerate input (family total > workspace total) must still yield a valid share in [0,1].
        var result = new ImpactSelectionPolicy().select(
                List.of(new FamilyImpact(FP, 10, new BigDecimal("500"), 10, 0, 0, ONE_SOURCE)),
                new BigDecimal("100"), 0);

        assertAll(
                () -> assertEquals(ImpactSelectionResult.Outcome.SELECTED, result.outcome()),
                () -> assertEquals(BigDecimal.ONE, result.candidates().get(0).assessment().delta().workspaceShare()));
    }

    @Test
    void customThresholdsWithTopNLimitTheSelection() {
        var policy = new ImpactSelectionPolicy(new BigDecimal("0.01"), BigDecimal.ONE, 1);
        var result = policy.select(List.of(
                new FamilyImpact(FP, 1, new BigDecimal("300"), 1, 0, 0, ONE_SOURCE),
                new FamilyImpact(new QueryFingerprint("hmac-sha256:" + "b".repeat(64), "v1"), 1,
                        new BigDecimal("200"), 1, 0, 0, List.of(new SourceQueryId("2")))),
                new BigDecimal("500"), 0);

        assertEquals(1, result.candidates().size());
    }

    @Test
    void valueObjectsRejectInvalidInput() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new QueryImpactDelta(-1, BigDecimal.TEN, 0, 0, 0, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new QueryImpactDelta(1, new BigDecimal("-1"), 0, 0, 0, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new QueryImpactDelta(1, BigDecimal.TEN, 0, 0, 0, new BigDecimal("1.5"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FamilyImpact(FP, 1, BigDecimal.TEN, 1, 0, 0, List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FamilyImpact(FP, -1, BigDecimal.TEN, 1, 0, 0, ONE_SOURCE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactAssessment(delta(), 0, BaselineConfidence.ABSENT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SelectionReason(SelectionReason.Code.TOP_TOTAL_EXEC_TIME, new BigDecimal("-1"), 1,
                                BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Candidate(FP, List.of(), new ImpactAssessment(delta(), 1, BaselineConfidence.ABSENT),
                                reason())));
    }

    @Test
    void resultFactoriesEnforceTheOutcomeInvariant() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionResult(List.of(), 0, ImpactSelectionResult.Outcome.SELECTED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ImpactSelectionResult(List.of(candidate()), 0,
                                ImpactSelectionResult.Outcome.WARMING)),
                () -> assertThrows(IllegalArgumentException.class, () -> ImpactSelectionResult.warming(-1)),
                () -> assertEquals(ImpactSelectionResult.Outcome.WARMING,
                        ImpactSelectionResult.warming(2).outcome()),
                () -> assertEquals(3, ImpactSelectionResult.none(3).unclassifiedCount()));
    }

    private static QueryImpactDelta delta() {
        return new QueryImpactDelta(1, BigDecimal.TEN, 1, 0, 0, new BigDecimal("0.5"));
    }

    private static SelectionReason reason() {
        return new SelectionReason(SelectionReason.Code.TOP_TOTAL_EXEC_TIME, BigDecimal.TEN, 1, new BigDecimal("0.5"));
    }

    private static Candidate candidate() {
        return new Candidate(FP, ONE_SOURCE, new ImpactAssessment(delta(), 1, BaselineConfidence.ABSENT), reason());
    }
}
