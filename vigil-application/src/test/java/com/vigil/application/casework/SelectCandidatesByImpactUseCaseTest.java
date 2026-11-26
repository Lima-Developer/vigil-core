package com.vigil.application.casework;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.FingerprintedQueryCounters;
import com.vigil.application.port.out.interfaces.ImpactTelemetrySnapshot;
import com.vigil.application.telemetry.QueryTelemetryDeltaCalculator;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.ImpactSelectionPolicy;
import com.vigil.core.domain.ImpactSelectionResult;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.SourceQueryId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectCandidatesByImpactUseCaseTest {
    private static final Instant T1 = Instant.parse("2026-11-24T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-24T12:05:00Z");
    private static final Instant RESET = Instant.parse("2026-11-24T11:00:00Z");
    private static final QueryFingerprint FP_A =
            new QueryFingerprint("hmac-sha256:" + "a".repeat(64), "v1");

    private final SelectCandidatesByImpactUseCase useCase =
            new SelectCandidatesByImpactUseCase(new QueryTelemetryDeltaCalculator(), new ImpactSelectionPolicy());

    @Test
    void sumsTwoKeysWithTheSameFingerprintIntoOneFamily() {
        var previous = snapshot(T1, RESET,
                entry(1, 1, 300, "0", Optional.of(FP_A)),
                entry(2, 2, 300, "0", Optional.of(FP_A)));
        var current = snapshot(T2, RESET,
                entry(1, 1, 300, "400", Optional.of(FP_A)),
                entry(2, 2, 300, "400", Optional.of(FP_A)));

        var result = useCase.execute(previous, current);
        Candidate candidate = result.candidates().get(0);

        assertAll(
                () -> assertEquals(ImpactSelectionResult.Outcome.SELECTED, result.outcome()),
                () -> assertEquals(1, result.candidates().size()),
                () -> assertEquals(FP_A, candidate.fingerprint()),
                () -> assertEquals(List.of(new SourceQueryId("1"), new SourceQueryId("2")),
                        candidate.sourceQueryIds()),
                () -> assertEquals(new BigDecimal("800"), candidate.assessment().delta().totalExecutionMillis()));
    }

    @Test
    void droppedUnclassifiedKeyIsCountedAndExcluded() {
        var previous = snapshot(T1, RESET,
                entry(1, 1, 300, "0", Optional.of(FP_A)),
                entry(9, 9, 10, "0", Optional.empty()));
        var current = snapshot(T2, RESET,
                entry(1, 1, 300, "800", Optional.of(FP_A)),
                entry(9, 9, 10, "200", Optional.empty()));

        var result = useCase.execute(previous, current);

        assertAll(
                () -> assertEquals(1, result.unclassifiedCount()),
                () -> assertEquals(1, result.candidates().size()),
                () -> assertTrue(result.candidates().get(0).sourceQueryIds()
                        .stream().noneMatch(id -> id.value().equals("9"))),
                // workspace share uses the full interval total (1000 ms): 800 / 1000 = 80%.
                () -> assertTrue(result.candidates().get(0).selectionReason().render()
                        .contains("80% do tempo do workspace")));
    }

    @Test
    void warmingYieldsNoCandidatesWithWarmingOutcome() {
        var previous = snapshot(T1, RESET, entry(1, 1, 300, "0", Optional.of(FP_A)));
        var current = snapshot(T2, Instant.parse("2026-11-24T11:30:00Z"),
                entry(1, 1, 300, "800", Optional.of(FP_A)));

        var result = useCase.execute(previous, current);

        assertAll(
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertEquals(ImpactSelectionResult.Outcome.WARMING, result.outcome()));
    }

    private static ImpactTelemetrySnapshot snapshot(Instant at, Instant statsReset,
                                                    FingerprintedQueryCounters... entries) {
        return new ImpactTelemetrySnapshot(at, Optional.of(statsReset), 0, true, List.of(entries), 0);
    }

    private static FingerprintedQueryCounters entry(long userid, long queryId, long calls, String totalMillis,
                                                    Optional<QueryFingerprint> fingerprint) {
        var counters = new CumulativeQueryCounters(1, userid, queryId, true, calls, new BigDecimal(totalMillis),
                calls, 0, 0, 0, 0);
        return new FingerprintedQueryCounters(counters, fingerprint);
    }
}
