package com.vigil.application.port.out.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CumulativeQueryTelemetryRecordsTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    private static CumulativeQueryCounters counters(long dbid, long userid, long calls, BigDecimal millis, long rows,
                                                    long hit, long read, long tempRead, long tempWritten) {
        return new CumulativeQueryCounters(dbid, userid, 1, true, calls, millis, rows, hit, read, tempRead, tempWritten);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void rejectsEachNegativeCounter(int index) {
        long[] v = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        v[index] = -1;
        assertThrows(IllegalArgumentException.class, () ->
                counters(v[0], v[1], v[2], BigDecimal.valueOf(v[3]), v[4], v[5], v[6], v[7], v[8]));
    }

    @Test
    void acceptsAllZeroesAndRejectsNullTime() {
        counters(0, 0, 0, BigDecimal.ZERO, 0, 0, 0, 0, 0);
        assertThrows(NullPointerException.class, () -> counters(0, 0, 0, null, 0, 0, 0, 0, 0));
    }

    @Test
    void collectionValidatesAndCopiesItsList() {
        var list = new ArrayList<CumulativeQueryCounters>();
        list.add(counters(1, 1, 1, BigDecimal.ONE, 1, 1, 1, 1, 1));
        var collection = new TelemetryCollection(NOW, Optional.empty(), 0, true, list, 0);
        list.clear();
        assertEquals(1, collection.queries().size());
        assertThrows(UnsupportedOperationException.class, () -> collection.queries().clear());
    }

    @Test
    void collectionRejectsInvalidArguments() {
        List<CumulativeQueryCounters> none = List.of();
        assertThrows(NullPointerException.class, () -> new TelemetryCollection(null, Optional.empty(), 0, true, none, 0));
        assertThrows(NullPointerException.class, () -> new TelemetryCollection(NOW, null, 0, true, none, 0));
        assertThrows(NullPointerException.class, () -> new TelemetryCollection(NOW, Optional.empty(), 0, true, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryCollection(NOW, Optional.empty(), -1, true, none, 0));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryCollection(NOW, Optional.empty(), 0, true, none, -1));
        new TelemetryCollection(NOW, Optional.empty(), 0, false, none, 0);
    }
}
