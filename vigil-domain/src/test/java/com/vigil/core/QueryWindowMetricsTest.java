package com.vigil.core;

import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.QueryCounters;
import com.vigil.core.domain.QueryWindowMetrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class QueryWindowMetricsTest {
    private static final ObservationWindow WINDOW = new ObservationWindow(
            Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:01:00Z"));

    private static QueryCounters delta(long calls) {
        return new QueryCounters(1, 2, -3, true, calls, new BigDecimal("1.000"),
                0, 0, 0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.500000", "0.499999", "0.5"})
    void completeResultRejectsIncoherentOrNoncanonicalMean(String mean) {
        assertThrows(IllegalArgumentException.class, () -> new QueryWindowMetrics(
                QueryWindowMetrics.Status.COMPLETE, Optional.of(WINDOW), Optional.of(delta(2)),
                Optional.of(new BigDecimal(mean))));
    }

    @Test void completeResultAcceptsOnlyComputedMean() {
        var result = new QueryWindowMetrics(QueryWindowMetrics.Status.COMPLETE, Optional.of(WINDOW),
                Optional.of(delta(2)), Optional.of(new BigDecimal("0.500000")));
        assertEquals(new BigDecimal("0.500000"), result.meanExecutionMillis().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new QueryWindowMetrics(
                QueryWindowMetrics.Status.COMPLETE, Optional.of(WINDOW), Optional.of(delta(2)), Optional.empty()));
    }

    @Test void zeroCallsMustHaveNoMean() {
        var result = new QueryWindowMetrics(QueryWindowMetrics.Status.COMPLETE, Optional.of(WINDOW),
                Optional.of(delta(0)), Optional.empty());
        assertTrue(result.meanExecutionMillis().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new QueryWindowMetrics(
                QueryWindowMetrics.Status.COMPLETE, Optional.of(WINDOW), Optional.of(delta(0)),
                Optional.of(BigDecimal.ZERO)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_BASELINE", "INVALID_WINDOW"})
    void statusWithoutValidWindowRejectsWindow(String status) {
        assertThrows(IllegalArgumentException.class, () -> new QueryWindowMetrics(
                QueryWindowMetrics.Status.valueOf(status), Optional.of(WINDOW), Optional.empty(), Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENTRY_NEW", "ENTRY_MISSING", "ENTRY_ABSENT", "RESET", "EVICTION", "INCOMPLETE"})
    void statusWithKnownEndpointsRequiresWindow(String status) {
        assertThrows(IllegalArgumentException.class, () -> new QueryWindowMetrics(
                QueryWindowMetrics.Status.valueOf(status), Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
