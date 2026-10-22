package com.vigil.adapters.postgres.pgstatstatements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PgStatStatementsRowMapperTest {

    private static PgStatStatementsRow valid() {
        return new PgStatStatementsRow(5L, 10L, -77L, true, 50L, 1234.5, 750L, 11L, 12L, 13L, 14L);
    }

    static Stream<Arguments> unusableRows() {
        return Stream.of(
                arg("dbid null", r -> new PgStatStatementsRow(null, r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("userid null", r -> new PgStatStatementsRow(r.dbid(), null, r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("queryid null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), null, r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("toplevel null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), null, r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("calls null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), null,
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("time null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        null, r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("rows null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), null, r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("hit null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), null, r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("read null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), null, r.tempBlksRead(), r.tempBlksWritten())),
                arg("temp read null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), null, r.tempBlksWritten())),
                arg("temp written null", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), null)),
                arg("dbid negative", r -> new PgStatStatementsRow(-1L, r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("userid negative", r -> new PgStatStatementsRow(r.dbid(), -1L, r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("calls negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), -1L,
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("rows negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), -1L, r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("hit negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), -1L, r.sharedBlksRead(), r.tempBlksRead(), r.tempBlksWritten())),
                arg("read negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), -1L, r.tempBlksRead(), r.tempBlksWritten())),
                arg("temp read negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), -1L, r.tempBlksWritten())),
                arg("temp written negative", r -> new PgStatStatementsRow(r.dbid(), r.userid(), r.queryId(), r.topLevel(), r.calls(),
                        r.totalExecTimeMillis(), r.rows(), r.sharedBlksHit(), r.sharedBlksRead(), r.tempBlksRead(), -1L)));
    }

    private static Arguments arg(String name, UnaryOperator<PgStatStatementsRow> change) {
        return Arguments.of(name, change.apply(valid()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableRows")
    void discardsUnusableRows(String name, PgStatStatementsRow row) {
        assertTrue(PgStatStatementsRowMapper.map(row).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001, -1e12})
    void discardsNonFiniteOrNegativeTime(double millis) {
        assertTrue(PgStatStatementsRowMapper.map(withTime(millis)).isEmpty());
    }

    @Test
    void mapsEveryFieldWithoutMixingColumns() {
        CumulativeQueryCounters counters = PgStatStatementsRowMapper.map(valid()).orElseThrow();

        assertEquals(new CumulativeQueryCounters(5, 10, -77, true, 50, new BigDecimal("1234.5"), 750, 11, 12, 13, 14),
                counters);
    }

    @Test
    void keepsTopLevelFalse() {
        var row = new PgStatStatementsRow(5L, 10L, 1L, false, 1L, 1.0, 1L, 1L, 1L, 1L, 1L);
        assertFalse(PgStatStatementsRowMapper.map(row).orElseThrow().topLevel());
    }

    @Test
    void acceptsZeroCountersAndTime() {
        var row = new PgStatStatementsRow(0L, 0L, 0L, true, 0L, 0.0, 0L, 0L, 0L, 0L, 0L);
        CumulativeQueryCounters counters = PgStatStatementsRowMapper.map(row).orElseThrow();
        assertEquals(0L, counters.calls());
        assertEquals(0, BigDecimal.ZERO.compareTo(counters.totalExecutionMillis()));
    }

    @Test
    void acceptsLargestCounters() {
        long max = Long.MAX_VALUE;
        var row = new PgStatStatementsRow(max, max, Long.MIN_VALUE, true, max, 1.0, max, max, max, max, max);
        CumulativeQueryCounters counters = PgStatStatementsRowMapper.map(row).orElseThrow();
        assertEquals(max, counters.calls());
        assertEquals(Long.MIN_VALUE, counters.queryId());
        assertEquals(max, counters.tempBlksWritten());
    }

    @Test
    void keepsFractionalMillisecondsExactly() {
        assertEquals(new BigDecimal("1234.5"),
                PgStatStatementsRowMapper.map(withTime(1234.5)).orElseThrow().totalExecutionMillis());
    }

    @Test
    void keepsTrillionMillisecondsExactlyBeyondDurationRange() {
        BigDecimal millis = PgStatStatementsRowMapper.map(withTime(1e12)).orElseThrow().totalExecutionMillis();
        assertEquals(0, new BigDecimal("1000000000000").compareTo(millis));
    }

    @Test
    void edgeBeyondLongNanosecondsIsNotTruncated() {
        BigDecimal millis = PgStatStatementsRowMapper.map(withTime(1e10)).orElseThrow().totalExecutionMillis();
        assertEquals(0, new BigDecimal("10000000000").compareTo(millis));
    }

    @Test
    void edgeTypesCarryNoText() {
        for (Class<?> type : new Class<?>[]{PgStatStatementsRow.class, CumulativeQueryCounters.class,
                TelemetryCollection.class}) {
            assertFalse(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType)
                            .anyMatch(String.class::equals), type.getSimpleName());
        }
    }

    private static PgStatStatementsRow withTime(double millis) {
        return new PgStatStatementsRow(5L, 10L, 1L, true, 1L, millis, 1L, 1L, 1L, 1L, 1L);
    }
}
