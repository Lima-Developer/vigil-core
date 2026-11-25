package com.vigil.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.core.domain.BaselineConfidence;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.FamilyImpact;
import com.vigil.core.domain.ImpactSelectionPolicy;
import com.vigil.core.domain.ImpactSelectionResult;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.SourceQueryId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImpactSelectionPolicyTest {
    private final ImpactSelectionPolicy policy = new ImpactSelectionPolicy();

    private static QueryFingerprint fp(char c) {
        return new QueryFingerprint("hmac-sha256:" + String.valueOf(c).repeat(64), "v1");
    }

    private static FamilyImpact family(QueryFingerprint fingerprint, long calls, String totalMillis) {
        return new FamilyImpact(fingerprint, calls, new BigDecimal(totalMillis), calls, 0, 0,
                List.of(new SourceQueryId("q" + fingerprint.value().charAt(12))));
    }

    @Test
    void ranksByAbsoluteTotalTimeAndBreaksTiesStably() {
        var result = policy.select(List.of(
                family(fp('b'), 5, "300"),   // tie on time with 'a' and 'c'; calls 5
                family(fp('c'), 9, "300"),   // tie on time; calls 9 -> ahead of the 5-call ones
                family(fp('a'), 5, "300"),   // tie on time; calls 5 -> fingerprint 'a' before 'b'
                family(fp('d'), 1, "900")),  // highest total time -> rank 1
                new BigDecimal("1800"), 0);

        var order = result.candidates().stream().map(c -> c.fingerprint().value().charAt(12)).toList();
        assertAll(
                () -> assertEquals(ImpactSelectionResult.Outcome.SELECTED, result.outcome()),
                () -> assertEquals(List.of('d', 'c', 'a', 'b'), order),
                () -> assertEquals(1, result.candidates().get(0).assessment().rank()),
                () -> assertEquals('d', result.candidates().get(0).fingerprint().value().charAt(12)));
    }

    @Test
    void manyCallsWithLowTotalTimeIsNotSelected() {
        // 'cheap' has the most calls but tiny total time; 'heavy' has fewer calls but dominates the time.
        var cheap = family(fp('c'), 10_000, "50");
        var heavy = family(fp('e'), 12, "800");

        var result = policy.select(List.of(cheap, heavy), new BigDecimal("1000"), 0);

        assertAll(
                () -> assertEquals(1, result.candidates().size()),
                () -> assertEquals('e', result.candidates().get(0).fingerprint().value().charAt(12)),
                () -> assertTrue(result.candidates().stream()
                        .noneMatch(c -> c.fingerprint().value().charAt(12) == 'c')));
    }

    @Test
    void belowShareOrBelowTimeIsNotEligibleAndEmptyCarriesAReason() {
        var belowTime = family(fp('a'), 3, "80");                 // 80 ms < 100 ms
        var belowShare = family(fp('b'), 50, "500");              // 500 / 100000 = 0.5% < 5%

        var result = policy.select(List.of(belowTime, belowShare), new BigDecimal("100000"), 0);

        assertAll(
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertEquals(ImpactSelectionResult.Outcome.NO_CANDIDATES_ABOVE_THRESHOLDS, result.outcome()));
    }

    @Test
    void selectedCandidateHasAbsentBaselineAndALiteralFreeReason() {
        var result = policy.select(List.of(family(fp('a'), 56, "1234")), new BigDecimal("3000"), 0);
        Candidate candidate = result.candidates().get(0);
        String reason = candidate.selectionReason().render();

        assertAll(
                () -> assertEquals(BaselineConfidence.ABSENT, candidate.assessment().baselineConfidence()),
                () -> assertTrue(reason.contains("1234 ms"), reason),
                () -> assertTrue(reason.contains("56 chamadas"), reason),
                () -> assertTrue(reason.contains("41% do tempo do workspace"), reason),
                () -> assertFalse(reason.contains("'"), reason),
                () -> assertFalse(reason.toLowerCase().contains("select"), reason));
    }

    @Test
    void sameInputAlwaysProducesTheSameOutput() {
        var families = List.of(family(fp('a'), 5, "300"), family(fp('b'), 5, "300"), family(fp('d'), 1, "900"));

        assertEquals(
                policy.select(families, new BigDecimal("1500"), 0).candidates().stream()
                        .map(c -> c.fingerprint().value()).toList(),
                policy.select(families, new BigDecimal("1500"), 0).candidates().stream()
                        .map(c -> c.fingerprint().value()).toList());
    }
}
