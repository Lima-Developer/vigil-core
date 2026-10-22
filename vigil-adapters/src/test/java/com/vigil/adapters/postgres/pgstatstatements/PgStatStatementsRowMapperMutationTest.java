package com.vigil.adapters.postgres.pgstatstatements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pins the boundaries the mapper must honour: zero is a valid counter, only strictly negative values are discarded,
 * and every column lands in its own field. Each assertion exists to kill a specific mutation.
 */
class PgStatStatementsRowMapperMutationTest {

    @Test
    void zeroIsAcceptedForEachCounterIndependently() {
        for (int zeroed = 0; zeroed < 8; zeroed++) {
            long[] values = {1, 2, 3, 4, 5, 6, 7, 8};
            values[zeroed] = 0;
            var row = new PgStatStatementsRow(1L, 2L, 3L, true, values[0], 1.0, values[1], values[2], values[3],
                    values[4], values[5]);
            assertTrue(PgStatStatementsRowMapper.map(row).isPresent(), "zero at " + zeroed);
        }
        assertTrue(PgStatStatementsRowMapper.map(new PgStatStatementsRow(0L, 0L, 0L, true, 1L, 1.0, 1L, 1L, 1L, 1L,
                1L)).isPresent());
    }

    @Test
    void minusOneIsRejectedForEachCounterIndependently() {
        for (int negative = 0; negative < 6; negative++) {
            long[] values = {1, 1, 1, 1, 1, 1};
            values[negative] = -1;
            var row = new PgStatStatementsRow(1L, 1L, 1L, true, values[0], 1.0, values[1], values[2], values[3],
                    values[4], values[5]);
            assertTrue(PgStatStatementsRowMapper.map(row).isEmpty(), "negative at " + negative);
        }
    }

    @Test
    void negativeQueryIdIsAValidHash() {
        assertTrue(PgStatStatementsRowMapper.map(new PgStatStatementsRow(1L, 1L, -1L, true, 1L, 1.0, 1L, 1L, 1L, 1L,
                1L)).isPresent());
    }

    @Test
    void zeroTimeIsAcceptedAndSmallestPositiveKept() {
        assertTrue(PgStatStatementsRowMapper.map(time(0.0)).isPresent());
        assertEquals(new BigDecimal(Double.toString(Double.MIN_VALUE)),
                PgStatStatementsRowMapper.map(time(Double.MIN_VALUE)).orElseThrow().totalExecutionMillis());
        assertFalse(PgStatStatementsRowMapper.map(time(-Double.MIN_VALUE)).isPresent());
    }

    @Test
    void distinctValuesLandInDistinctFields() {
        var counters = PgStatStatementsRowMapper.map(new PgStatStatementsRow(1L, 2L, 3L, false, 4L, 5.5, 6L, 7L, 8L,
                9L, 10L)).orElseThrow();
        assertEquals(1L, counters.dbid());
        assertEquals(2L, counters.userid());
        assertEquals(3L, counters.queryId());
        assertFalse(counters.topLevel());
        assertEquals(4L, counters.calls());
        assertEquals(new BigDecimal("5.5"), counters.totalExecutionMillis());
        assertEquals(6L, counters.rows());
        assertEquals(7L, counters.sharedBlksHit());
        assertEquals(8L, counters.sharedBlksRead());
        assertEquals(9L, counters.tempBlksRead());
        assertEquals(10L, counters.tempBlksWritten());
    }

    private static PgStatStatementsRow time(double millis) {
        return new PgStatStatementsRow(1L, 1L, 1L, true, 1L, millis, 1L, 1L, 1L, 1L, 1L);
    }
}
