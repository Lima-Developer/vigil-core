package com.vigil.bootstrap;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.DatabaseActivitySnapshot;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContinuousTelemetryCollectorTest {
    private static final Instant FIRST = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    void storesAWarmupThenAMeasuredDeltaAndKeepsOnlyAggregateData() {
        var now = new AtomicReference<>(FIRST);
        var reads = new AtomicInteger();
        var history = new MemoryHistory();
        try (var collector = new ContinuousTelemetryCollector(
                at -> snapshot(at, reads.getAndIncrement() == 0 ? 10 : 15),
                at -> new DatabaseActivitySnapshot(at, 3, 2, 1, true),
                history, now::get, "lab", Duration.ofSeconds(30), Duration.ofHours(1), 120)) {
            collector.collectNow();
            now.set(FIRST.plusSeconds(30));
            collector.collectNow();

            assertEquals(2, history.samples.size());
            var warming = history.samples.get(0);
            var measured = history.samples.get(1);
            assertAll(
                    () -> assertEquals("warming_up", warming.state()),
                    () -> assertEquals("FIRST_SAMPLE", warming.reason()),
                    () -> assertEquals(2L, warming.waitingSessions()),
                    () -> assertEquals("available", measured.state()),
                    () -> assertEquals(5L, measured.calls()),
                    () -> assertEquals(new BigDecimal("50"), measured.executionMillis()),
                    () -> assertEquals(new BigDecimal("10.000000"), measured.meanExecutionMillis()),
                    () -> assertEquals(FIRST, measured.windowStart()),
                    () -> assertEquals(FIRST.plusSeconds(30), collector.status().lastSuccess()),
                    () -> assertEquals(FIRST.minus(Duration.ofHours(1)), history.deleteBefore.get(0))
            );
        }
    }

    @Test
    void recordsFailuresWithoutSyntheticZerosAndUsesBoundedExponentialBackoff() {
        var attempts = new AtomicInteger();
        var history = new MemoryHistory();
        try (var collector = new ContinuousTelemetryCollector(
                at -> {
                    if (attempts.getAndIncrement() < 2) throw new IllegalStateException("secret details");
                    return snapshot(at, 10);
                },
                at -> new DatabaseActivitySnapshot(at, 0, 0, 0, true),
                history, () -> FIRST, "lab", Duration.ofSeconds(30), Duration.ofHours(1), 120)) {
            collector.collectNow();
            collector.collectNow();

            assertAll(
                    () -> assertEquals(2, collector.status().consecutiveFailures()),
                    () -> assertEquals("IllegalStateException", collector.status().lastFailure()),
                    () -> assertEquals(Duration.ofSeconds(120), collector.retryDelay()),
                    () -> assertTrue(history.samples.stream().allMatch(sample -> sample.calls() == null)),
                    () -> assertTrue(history.samples.stream().allMatch(sample -> "COLLECTION_FAILED".equals(sample.reason())))
            );

            collector.collectNow();

            assertAll(
                    () -> assertEquals(0, collector.status().consecutiveFailures()),
                    () -> assertNull(collector.status().lastFailure()),
                    () -> assertEquals(Duration.ofSeconds(30), collector.retryDelay()),
                    () -> assertEquals("warming_up", history.samples.getLast().state())
            );
        }
    }

    @Test
    void exposesPauseStateWithoutStartingBackgroundWork() {
        try (var collector = new ContinuousTelemetryCollector(
                at -> snapshot(at, 1),
                at -> new DatabaseActivitySnapshot(at, 0, 0, 0, true),
                new MemoryHistory(), () -> FIRST, "lab", Duration.ofSeconds(30), Duration.ofHours(1), 2)) {
            collector.pause();
            assertTrue(collector.status().paused());
            collector.resume();
            assertFalse(collector.status().paused());
            assertFalse(collector.status().running());
        }
    }

    @Test
    void recordsPartialVisibilityAndValidatesCollectorBounds() {
        var history = new MemoryHistory();
        try (var collector = new ContinuousTelemetryCollector(
                at -> snapshot(at, 1),
                at -> new DatabaseActivitySnapshot(at, 1, 1, 1, false),
                history, () -> FIRST, "lab", Duration.ofSeconds(1), Duration.ofSeconds(1), 2)) {
            collector.collectNow();
            var sample = history.samples.getFirst();
            assertEquals("partial", sample.quality());
            assertEquals("FIRST_SAMPLE;PARTIAL_ACTIVITY_VISIBILITY", sample.reason());
            collector.start();
            assertTrue(collector.isRunning());
            collector.stop();
            assertFalse(collector.isRunning());
        }
        assertThrows(NullPointerException.class, () -> new ContinuousTelemetryCollector(null, at -> null,
                new MemoryHistory(), () -> FIRST, "lab", Duration.ofSeconds(1), Duration.ofSeconds(1), 2));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousTelemetryCollector(at -> snapshot(at, 1), at -> null,
                new MemoryHistory(), () -> FIRST, "lab", Duration.ZERO, Duration.ofSeconds(1), 2));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousTelemetryCollector(at -> snapshot(at, 1), at -> null,
                new MemoryHistory(), () -> FIRST, "lab", Duration.ofSeconds(1), Duration.ZERO, 2));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousTelemetryCollector(at -> snapshot(at, 1), at -> null,
                new MemoryHistory(), () -> FIRST, "lab", Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousTelemetryCollector(at -> snapshot(at, 1), at -> null,
                new MemoryHistory(), () -> FIRST, "lab", Duration.ofSeconds(1), Duration.ofSeconds(1), 100_001));
    }

    private static TelemetryCollection snapshot(Instant at, long calls) {
        var counters = new CumulativeQueryCounters(1, 2, 3, true, calls,
                BigDecimal.valueOf(calls * 10), calls * 2, calls * 3, calls, 0, 0);
        return new TelemetryCollection(at, Optional.empty(), 0, true, List.of(counters), 0);
    }

    private static final class MemoryHistory implements WorkspaceTelemetryHistory {
        private final List<WorkspaceTelemetrySample> samples = new ArrayList<>();
        private final List<Instant> deleteBefore = new ArrayList<>();

        @Override
        public void append(WorkspaceTelemetrySample sample, Instant deleteBefore, int maximumSamples) {
            samples.add(sample);
            this.deleteBefore.add(deleteBefore);
        }

        @Override
        public List<WorkspaceTelemetrySample> recent(int limit) {
            return samples.reversed().stream().limit(limit).toList();
        }
    }
}
