package com.vigil.application.port.out.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceTelemetrySampleTest {
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void acceptsEverySupportedAvailabilityShape() {
        assertDoesNotThrow(() -> available("measured", null));
        assertDoesNotThrow(() -> available("partial", "PARTIAL_ACTIVITY_VISIBILITY"));
        assertDoesNotThrow(() -> unavailable("warming_up", "measured", "FIRST_SAMPLE"));
        assertDoesNotThrow(() -> unavailable("warming_up", "partial", "PARTIAL_ACTIVITY_VISIBILITY"));
        assertDoesNotThrow(() -> unavailable("error", "unknown", "COLLECTION_FAILED"));
    }

    @Test
    void rejectsUnknownStatesAndQualities() {
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> sample.state = "other"));
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> sample.quality = "other"));
        assertThrows(NullPointerException.class, () -> copy(sample -> sample.observedAt = null));
        assertThrows(NullPointerException.class, () -> copy(sample -> sample.targetId = null));
        assertThrows(NullPointerException.class, () -> copy(sample -> sample.state = null));
        assertThrows(NullPointerException.class, () -> copy(sample -> sample.quality = null));
    }

    @Test
    void rejectsInconsistentAvailabilityAndReasons() {
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> sample.windowStart = null));
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> {
            sample.state = "warming_up";
            sample.reason = "FIRST_SAMPLE";
        }));
        assertThrows(IllegalArgumentException.class, () -> available("unknown", "not allowed"));
        assertThrows(IllegalArgumentException.class, () -> available("measured", "not allowed"));
        assertThrows(IllegalArgumentException.class, () -> unavailable("error", "measured", "failure"));
        assertThrows(IllegalArgumentException.class, () -> unavailable("warming_up", "partial", null));
        assertThrows(IllegalArgumentException.class, () -> unavailable("warming_up", "partial", "   "));
        assertThrows(IllegalArgumentException.class, () -> unavailable("error", "unknown", null));
    }

    @Test
    void requiresEveryCounterForAnAvailableSample() {
        List<Consumer<MutableSample>> omissions = List.of(
                sample -> sample.calls = null,
                sample -> sample.executionMillis = null,
                sample -> sample.rows = null,
                sample -> sample.sharedHits = null,
                sample -> sample.sharedReads = null,
                sample -> sample.tempReads = null,
                sample -> sample.tempWrites = null,
                sample -> sample.activeSessions = null,
                sample -> sample.waitingSessions = null,
                sample -> sample.blockedSessions = null
        );
        for (Consumer<MutableSample> omission : omissions) {
            assertThrows(IllegalArgumentException.class, () -> copy(omission));
        }
    }

    @Test
    void rejectsDeltasWhenTheCollectorIsUnavailable() {
        List<Consumer<MutableSample>> deltas = List.of(
                sample -> sample.calls = 1L,
                sample -> sample.executionMillis = BigDecimal.ONE,
                sample -> sample.meanExecutionMillis = BigDecimal.ONE,
                sample -> sample.rows = 1L,
                sample -> sample.sharedHits = 1L,
                sample -> sample.sharedReads = 1L,
                sample -> sample.tempReads = 1L,
                sample -> sample.tempWrites = 1L
        );
        for (Consumer<MutableSample> delta : deltas) {
            assertThrows(IllegalArgumentException.class, () -> unavailableWith(delta));
        }
    }

    @Test
    void rejectsEveryNegativeCounterAndDuration() {
        List<Consumer<MutableSample>> negativeCounters = List.of(
                sample -> sample.calls = -1L,
                sample -> sample.rows = -1L,
                sample -> sample.sharedHits = -1L,
                sample -> sample.sharedReads = -1L,
                sample -> sample.tempReads = -1L,
                sample -> sample.tempWrites = -1L,
                sample -> sample.activeSessions = -1L,
                sample -> sample.waitingSessions = -1L,
                sample -> sample.blockedSessions = -1L
        );
        for (Consumer<MutableSample> negative : negativeCounters) {
            assertThrows(IllegalArgumentException.class, () -> copy(negative));
        }
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> sample.executionMillis = BigDecimal.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> copy(sample -> sample.meanExecutionMillis = BigDecimal.valueOf(-1)));
    }

    private static WorkspaceTelemetrySample available(String quality, String reason) {
        return new WorkspaceTelemetrySample(NOW, NOW.minusSeconds(30), "lab", "available", quality,
                1L, BigDecimal.TEN, BigDecimal.TEN, 1L, 1L, 1L, 0L, 0L, 1L, 0L, 0L, reason);
    }

    private static WorkspaceTelemetrySample unavailable(String state, String quality, String reason) {
        return new WorkspaceTelemetrySample(NOW, null, "lab", state, quality,
                null, null, null, null, null, null, null, null, 1L, 0L, 0L, reason);
    }

    private static WorkspaceTelemetrySample unavailableWith(Consumer<MutableSample> change) {
        var sample = new MutableSample();
        sample.state = "warming_up";
        sample.quality = "measured";
        sample.reason = "FIRST_SAMPLE";
        change.accept(sample);
        return sample.build();
    }

    private static WorkspaceTelemetrySample copy(Consumer<MutableSample> change) {
        var sample = new MutableSample();
        change.accept(sample);
        return sample.build();
    }

    private static final class MutableSample {
        private Instant observedAt = NOW;
        private Instant windowStart = NOW.minusSeconds(30);
        private String targetId = "lab";
        private String state = "available";
        private String quality = "measured";
        private Long calls = 1L;
        private BigDecimal executionMillis = BigDecimal.TEN;
        private BigDecimal meanExecutionMillis = BigDecimal.TEN;
        private Long rows = 1L;
        private Long sharedHits = 1L;
        private Long sharedReads = 1L;
        private Long tempReads = 0L;
        private Long tempWrites = 0L;
        private Long activeSessions = 1L;
        private Long waitingSessions = 0L;
        private Long blockedSessions = 0L;
        private String reason;

        private WorkspaceTelemetrySample build() {
            return new WorkspaceTelemetrySample(observedAt, windowStart, targetId, state, quality, calls,
                    executionMillis, meanExecutionMillis, rows, sharedHits, sharedReads, tempReads, tempWrites,
                    activeSessions, waitingSessions, blockedSessions, reason);
        }
    }
}
