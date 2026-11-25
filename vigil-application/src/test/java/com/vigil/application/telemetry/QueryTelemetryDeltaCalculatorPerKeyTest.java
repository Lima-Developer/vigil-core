package com.vigil.application.telemetry;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueryTelemetryDeltaCalculatorPerKeyTest {
    private static final Instant T1 = Instant.parse("2026-11-24T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-24T12:05:00Z");
    private static final Optional<Instant> RESET = Optional.of(Instant.parse("2026-11-24T11:00:00Z"));

    private final QueryTelemetryDeltaCalculator calculator = new QueryTelemetryDeltaCalculator();

    @Test
    void firstSampleAndNonMonotonicTimeWarm() {
        assertAll(
                () -> assertTrue(calculator.perKeyDeltas(null, collection(T2, counters(1, 10, "100"))).isWarming()),
                () -> assertEquals("NON_MONOTONIC_TIME", calculator.perKeyDeltas(
                        collection(T2, counters(1, 10, "100")),
                        collection(T1, counters(1, 20, "200"))).reason()));
    }

    @Test
    void regressionWarmsInsteadOfProducingNegativeDeltas() {
        var result = calculator.perKeyDeltas(
                collection(T1, counters(1, 20, "200")),
                collection(T2, counters(1, 10, "100")));

        assertEquals("COUNTER_REGRESSION", result.reason());
    }

    @Test
    void producesPerKeyDeltasAndFlagsPartialOnEntryChurn() {
        var result = calculator.perKeyDeltas(
                collection(T1, counters(1, 10, "100")),
                collection(T2, counters(1, 15, "250"), counters(2, 3, "40")));

        assertAll(
                () -> assertFalse(result.isWarming()),
                () -> assertEquals(1, result.deltas().size()),
                () -> assertEquals(5, result.deltas().get(0).calls()),
                () -> assertEquals(new BigDecimal("150"), result.deltas().get(0).executionMillis()),
                () -> assertTrue(result.partial()));
    }

    private static TelemetryCollection collection(Instant at, CumulativeQueryCounters... counters) {
        return new TelemetryCollection(at, RESET, 0, true, List.of(counters), 0);
    }

    private static CumulativeQueryCounters counters(long queryId, long calls, String millis) {
        return new CumulativeQueryCounters(1, 1, queryId, true, calls, new BigDecimal(millis), calls, 0, 0, 0, 0);
    }
}
