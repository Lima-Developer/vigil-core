package com.vigil.core;

import com.vigil.core.domain.QueryCounters;
import com.vigil.core.domain.QueryWindowMetrics;
import com.vigil.core.domain.TelemetrySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetrySnapshotTest {
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant END = START.plusSeconds(60);
    private static final Instant RESET = START.minusSeconds(3600);
    private static final QueryCounters.Key KEY = new QueryCounters.Key(10, 20, -30, true);

    private static QueryCounters counters(long calls, String millis, long rows, long hit, long read,
                                          long tempRead, long tempWritten) {
        return new QueryCounters(10, 20, -30, true, calls, new BigDecimal(millis), rows,
                hit, read, tempRead, tempWritten);
    }

    private static TelemetrySnapshot snapshot(Instant at, long dealloc, boolean visible, int skipped,
                                              QueryCounters... counters) {
        return new TelemetrySnapshot(at, Optional.of(RESET), dealloc, visible, skipped, List.of(counters));
    }

    @Test void subtractsAllCountersAndComputesMeanFromTotals() {
        var before = snapshot(START, 0, true, 0, counters(2, "2.000", 5, 6, 7, 8, 9));
        var after = snapshot(END, 0, true, 0, counters(5, "3.000", 11, 16, 17, 18, 19));
        var result = QueryWindowMetrics.between(Optional.of(before), after, KEY);

        assertEquals(QueryWindowMetrics.Status.COMPLETE, result.status());
        assertEquals(START, result.window().orElseThrow().startedAtExclusive());
        assertEquals(END, result.window().orElseThrow().endedAtInclusive());
        var delta = result.delta().orElseThrow();
        assertEquals(KEY, delta.key());
        assertEquals(3, delta.calls());
        assertEquals(0, new BigDecimal("1.000").compareTo(delta.totalExecutionMillis()));
        assertEquals(6, delta.rows());
        assertEquals(10, delta.sharedBlksHit());
        assertEquals(10, delta.sharedBlksRead());
        assertEquals(10, delta.tempBlksRead());
        assertEquals(10, delta.tempBlksWritten());
        assertEquals(new BigDecimal("0.333333"), result.meanExecutionMillis().orElseThrow());
        assertEquals(result, QueryWindowMetrics.between(Optional.of(before), after, KEY));
    }

    @Test void baselineAndNewEntryDifferFromZeroDelta() {
        var current = snapshot(END, 0, true, 0, counters(2, "2.5", 3, 4, 5, 6, 7));
        assertEquals(QueryWindowMetrics.Status.NO_BASELINE,
                QueryWindowMetrics.between(Optional.empty(), current, KEY).status());
        assertEquals(QueryWindowMetrics.Status.ENTRY_NEW,
                QueryWindowMetrics.between(Optional.of(snapshot(START, 0, true, 0)), current, KEY).status());
        var zero = QueryWindowMetrics.between(Optional.of(snapshot(START, 0, true, 0, current.queries().getFirst())),
                current, KEY);
        assertEquals(QueryWindowMetrics.Status.COMPLETE, zero.status());
        assertEquals(0, zero.delta().orElseThrow().calls());
        assertTrue(zero.meanExecutionMillis().isEmpty());
    }

    @Test void removedEntryNeverProducesZero() {
        var before = snapshot(START, 0, true, 0, counters(1, "1", 1, 1, 1, 1, 1));
        var result = QueryWindowMetrics.between(Optional.of(before), snapshot(END, 0, true, 0), KEY);
        assertEquals(QueryWindowMetrics.Status.ENTRY_MISSING, result.status());
        assertTrue(result.delta().isEmpty());
    }

    @Test void identityAbsentAtBothEndpointsIsNotARemoval() {
        var before = snapshot(START, 0, true, 0);
        var after = snapshot(END, 0, true, 0);
        var result = QueryWindowMetrics.between(Optional.of(before), after, KEY);
        assertEquals(QueryWindowMetrics.Status.ENTRY_ABSENT, result.status());
        assertTrue(result.delta().isEmpty());
        assertTrue(result.meanExecutionMillis().isEmpty());
    }

    @Test void batchComparisonReusesGlobalValidationAndPreservesAllIdentities() {
        var unchanged = counters(2, "2", 2, 2, 2, 2, 2);
        var removed = new QueryCounters(11, 20, -30, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1);
        var added = new QueryCounters(10, 20, -31, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1);
        var before = snapshot(START, 0, true, 0, unchanged, removed);
        var after = snapshot(END, 0, true, 0, unchanged, added);

        var results = QueryWindowMetrics.betweenAll(Optional.of(before), after);
        assertEquals(3, results.size());
        assertEquals(QueryWindowMetrics.Status.COMPLETE, results.get(KEY).status());
        assertEquals(QueryWindowMetrics.Status.ENTRY_MISSING, results.get(removed.key()).status());
        assertEquals(QueryWindowMetrics.Status.ENTRY_NEW, results.get(added.key()).status());
        assertEquals(QueryWindowMetrics.between(Optional.of(before), after, KEY), results.get(KEY));
        assertEquals(results, QueryWindowMetrics.betweenAll(Optional.of(before), after));
        assertThrows(UnsupportedOperationException.class, () -> results.clear());

        var regressed = snapshot(END, 0, true, 0, unchanged,
                new QueryCounters(11, 20, -30, true, 0, BigDecimal.ONE, 1, 1, 1, 1, 1));
        assertEquals(QueryWindowMetrics.Status.RESET,
                QueryWindowMetrics.betweenAll(Optional.of(before), regressed).get(KEY).status());
        assertEquals(QueryWindowMetrics.Status.NO_BASELINE,
                QueryWindowMetrics.betweenAll(Optional.empty(), after).get(KEY).status());
    }

    @Test void resetAndRegressionsNeverProduceNegativeMetrics() {
        var before = snapshot(START, 0, true, 0, counters(2, "2.0", 5, 5, 5, 5, 5));
        var reset = new TelemetrySnapshot(END, Optional.of(RESET.plusSeconds(1)), 0, true, 0,
                List.of(counters(3, "3.0", 6, 6, 6, 6, 6)));
        assertEquals(QueryWindowMetrics.Status.RESET, QueryWindowMetrics.between(Optional.of(before), reset, KEY).status());
        var regression = snapshot(END, 0, true, 0, counters(3, "3.0", 6, 4, 6, 6, 6));
        var result = QueryWindowMetrics.between(Optional.of(before), regression, KEY);
        assertEquals(QueryWindowMetrics.Status.RESET, result.status());
        assertTrue(result.delta().isEmpty());
        assertEquals(QueryWindowMetrics.Status.RESET,
                QueryWindowMetrics.between(Optional.of(before), snapshot(END, 0, true, 0, counters(1, "3", 6, 6, 6, 6, 6)), KEY).status());
    }

    @Test void globalEvictionAndIncompleteReadInvalidateWindow() {
        var before = snapshot(START, 1, true, 0, counters(2, "2", 2, 2, 2, 2, 2));
        var after = snapshot(END, 2, true, 0, counters(3, "3", 3, 3, 3, 3, 3));
        assertEquals(QueryWindowMetrics.Status.EVICTION, QueryWindowMetrics.between(Optional.of(before), after, KEY).status());
        assertEquals(QueryWindowMetrics.Status.RESET,
                QueryWindowMetrics.between(Optional.of(before), snapshot(END, 0, true, 0, after.queries().getFirst()), KEY).status());
        assertEquals(QueryWindowMetrics.Status.INCOMPLETE,
                QueryWindowMetrics.between(Optional.of(before), snapshot(END, 1, false, 0, after.queries().getFirst()), KEY).status());
        assertEquals(QueryWindowMetrics.Status.INCOMPLETE,
                QueryWindowMetrics.between(Optional.of(before), snapshot(END, 1, true, 1, after.queries().getFirst()), KEY).status());
    }

    @Test void equalOrReversedTimestampsCannotMakeAWindow() {
        var before = snapshot(START, 0, true, 0, counters(1, "1", 1, 1, 1, 1, 1));
        assertEquals(QueryWindowMetrics.Status.INVALID_WINDOW,
                QueryWindowMetrics.between(Optional.of(before), snapshot(START, 0, true, 0, before.queries().getFirst()), KEY).status());
        assertEquals(QueryWindowMetrics.Status.INVALID_WINDOW,
                QueryWindowMetrics.between(Optional.of(before), snapshot(START.minusSeconds(1), 0, true, 0, before.queries().getFirst()), KEY).status());
    }

    @Test void identityUsesEveryComponentIncludingSignedQueryId() {
        var one = counters(1, "1", 1, 1, 1, 1, 1);
        var other = new QueryCounters(10, 21, -30, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1);
        var before = snapshot(START, 0, true, 0, one, other);
        var after = snapshot(END, 0, true, 0, one, other);
        assertEquals(QueryWindowMetrics.Status.COMPLETE,
                QueryWindowMetrics.between(Optional.of(before), after, KEY).status());
        assertEquals(QueryWindowMetrics.Status.COMPLETE,
                QueryWindowMetrics.between(Optional.of(before), after, other.key()).status());
        assertEquals(QueryWindowMetrics.Status.ENTRY_MISSING,
                QueryWindowMetrics.between(Optional.of(before), snapshot(END, 0, true, 0, one), other.key()).status());
    }

    @Test void eachIdentityComponentSeparatesSeries() {
        var original = counters(1, "1", 1, 1, 1, 1, 1);
        var variants = List.of(
                new QueryCounters(11, 20, -30, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1),
                new QueryCounters(10, 21, -30, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1),
                new QueryCounters(10, 20, 30, true, 1, BigDecimal.ONE, 1, 1, 1, 1, 1),
                new QueryCounters(10, 20, -30, false, 1, BigDecimal.ONE, 1, 1, 1, 1, 1));
        var before = snapshot(START, 0, true, 0, original);
        for (var variant : variants) {
            var after = snapshot(END, 0, true, 0, variant);
            assertEquals(QueryWindowMetrics.Status.ENTRY_MISSING,
                    QueryWindowMetrics.between(Optional.of(before), after, KEY).status());
            assertEquals(QueryWindowMetrics.Status.ENTRY_NEW,
                    QueryWindowMetrics.between(Optional.of(before), after, variant.key()).status());
        }
    }

    @Test void snapshotCopiesTheListAndRejectsDuplicateIdentity() {
        var query = counters(1, "1", 1, 1, 1, 1, 1);
        var source = new ArrayList<>(List.of(query));
        var snapshot = new TelemetrySnapshot(START, Optional.empty(), 0, true, 0, source);
        source.clear();
        assertEquals(List.of(query), snapshot.queries());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.queries().add(query));
        assertThrows(IllegalArgumentException.class, () -> snapshot(START, 0, true, 0, query, query));
    }

    @Test void constructorsRejectInvalidAndIncompleteValues() {
        assertThrows(IllegalArgumentException.class, () -> counters(-1, "1", 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> counters(1, "-0.1", 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new QueryCounters.Key(-1, 1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> snapshot(START, -1, true, 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot(START, 0, true, -1));
        assertThrows(NullPointerException.class, () -> new TelemetrySnapshot(START, Optional.empty(), 0, true, 0, java.util.Arrays.asList((QueryCounters) null)));
    }
}
