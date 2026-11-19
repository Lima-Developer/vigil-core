package com.vigil.core.domain;

import com.vigil.core.domain.enums.PlanNodeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainContractExtraTest {
    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void protectsAllCounterInvariantsAndRegressionPaths() {
        var earlier = counters(5, BigDecimal.TEN, 5, 5, 5, 5, 5);
        assertFalse(counters(5, BigDecimal.TEN, 5, 5, 5, 5, 5).regressedFrom(earlier));
        for (Consumer<MutableCounters> change : List.<Consumer<MutableCounters>>of(
                value -> value.dbid = -1, value -> value.userid = -1, value -> value.calls = -1,
                value -> value.total = BigDecimal.valueOf(-1), value -> value.rows = -1,
                value -> value.hits = -1, value -> value.reads = -1, value -> value.tempReads = -1,
                value -> value.tempWrites = -1)) {
            assertThrows(IllegalArgumentException.class, () -> changed(change));
        }
        assertThrows(NullPointerException.class, () -> new QueryCounters(1, 1, 1, true, 1, null, 1, 1, 1, 1, 1));
        assertTrue(counters(4, BigDecimal.TEN, 5, 5, 5, 5, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.valueOf(9), 5, 5, 5, 5, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.TEN, 4, 5, 5, 5, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.TEN, 5, 4, 5, 5, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.TEN, 5, 5, 4, 5, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.TEN, 5, 5, 5, 4, 5).regressedFrom(earlier));
        assertTrue(counters(5, BigDecimal.TEN, 5, 5, 5, 5, 4).regressedFrom(earlier));
    }

    @Test
    void validatesEvidenceWindowsMetadataAndRedactedValueObjects() {
        var window = new ObservationWindow(START, START.plusSeconds(1));
        assertTrue(window.contains(START.plusMillis(1)));
        assertTrue(window.contains(START.plusSeconds(1)));
        assertFalse(window.contains(START));
        assertFalse(window.contains(START.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new ObservationWindow(null, START));
        assertThrows(IllegalArgumentException.class, () -> new ObservationWindow(START, null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationWindow(START, START));
        assertThrows(IllegalArgumentException.class, () -> window.contains(null));

        assertEquals(com.vigil.core.domain.enums.EvidenceType.PLAN_SUMMARY,
                new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 0).type());
        assertThrows(NullPointerException.class, () -> new PlanSummaryEvidencePayload(null, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PlanSummaryEvidencePayload(List.of(), 0, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 0, null));
        assertThrows(IllegalArgumentException.class, () -> new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), -1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 0, Optional.of(BigDecimal.valueOf(-1))));

        assertDoesNotThrow(() -> new DomainEventMetadata(UUID.randomUUID(), "actor", START, "1"));
        assertThrows(NullPointerException.class, () -> new DomainEventMetadata(null, "actor", START, "1"));
        assertThrows(IllegalArgumentException.class, () -> new DomainEventMetadata(UUID.randomUUID(), " ", START, "1"));
        assertThrows(NullPointerException.class, () -> new DomainEventMetadata(UUID.randomUUID(), "actor", null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new DomainEventMetadata(UUID.randomUUID(), "actor", START, " "));

        assertEquals("NormalizedQueryTemplate[redacted]", new NormalizedQueryTemplate("select 1").toString());
        assertThrows(IllegalArgumentException.class, () -> new NormalizedQueryTemplate(null));
        assertThrows(IllegalArgumentException.class, () -> new NormalizedQueryTemplate(" "));
        var reference = new ProtectedEvidenceReference("opaque");
        assertEquals("opaque", reference.opaqueIdentifier());
        assertEquals("ProtectedEvidenceReference[redacted]", reference.toString());
        assertEquals(reference, new ProtectedEvidenceReference("opaque"));
        assertNotEquals(reference, new ProtectedEvidenceReference("other"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedEvidenceReference(null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedEvidenceReference(" "));
    }

    private static QueryCounters counters(long calls, BigDecimal total, long rows, long hits, long reads,
                                          long tempReads, long tempWrites) {
        return new QueryCounters(1, 1, 1, true, calls, total, rows, hits, reads, tempReads, tempWrites);
    }

    private static QueryCounters changed(Consumer<MutableCounters> change) {
        var value = new MutableCounters();
        change.accept(value);
        return new QueryCounters(value.dbid, value.userid, 1, true, value.calls, value.total, value.rows,
                value.hits, value.reads, value.tempReads, value.tempWrites);
    }

    private static final class MutableCounters {
        private long dbid = 1, userid = 1, calls = 1, rows = 1, hits = 1, reads = 1, tempReads = 1, tempWrites = 1;
        private BigDecimal total = BigDecimal.ONE;
    }
}
