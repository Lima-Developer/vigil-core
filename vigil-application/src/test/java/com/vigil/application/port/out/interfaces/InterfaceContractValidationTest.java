package com.vigil.application.port.out.interfaces;

import com.vigil.application.casework.HumanDecision;
import com.vigil.application.casework.RecordHumanDecisionCommand;
import com.vigil.application.telemetry.AggregatedTelemetryDelta;
import com.vigil.core.domain.enums.ValidationOutcome;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterfaceContractValidationTest {
    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void validatesEveryInferenceRequestAndResponseBoundary() {
        assertDoesNotThrow(() -> new InferencePort.InferenceRequest("model", "prompt", Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> new InferencePort.InferenceResponse("model", "content", 0, 0, Duration.ZERO));
        for (InferenceRequestFactory invalid : List.<InferenceRequestFactory>of(
                () -> new InferencePort.InferenceRequest(null, "prompt", Duration.ofSeconds(1)),
                () -> new InferencePort.InferenceRequest(" ", "prompt", Duration.ofSeconds(1)),
                () -> new InferencePort.InferenceRequest("model", null, Duration.ofSeconds(1)),
                () -> new InferencePort.InferenceRequest("model", " ", Duration.ofSeconds(1)),
                () -> new InferencePort.InferenceRequest("model", "prompt", null),
                () -> new InferencePort.InferenceRequest("model", "prompt", Duration.ZERO),
                () -> new InferencePort.InferenceRequest("model", "prompt", Duration.ofMillis(-1)))) {
            assertThrows(IllegalArgumentException.class, invalid::create);
        }
        for (InferenceResponseFactory invalid : List.<InferenceResponseFactory>of(
                () -> new InferencePort.InferenceResponse(null, "content", 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse(" ", "content", 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", null, 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", " ", 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", "content", -1, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", "content", 0, -1, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", "content", 0, 0, null),
                () -> new InferencePort.InferenceResponse("model", "content", 0, 0, Duration.ofMillis(-1)))) {
            assertThrows(IllegalArgumentException.class, invalid::create);
        }
    }

    @Test
    void validatesAuditedEventsAndValidationAssessments() {
        assertDoesNotThrow(() -> new AuditedEvent("CASE_APPROVED", "reviewer", START, "1"));
        assertDoesNotThrow(() -> new HypothesisValidationPort.ValidationAssessment(ValidationOutcome.VALIDATED, "gain"));
        for (AuditedEventFactory invalid : List.<AuditedEventFactory>of(
                () -> new AuditedEvent(null, "actor", START, "1"),
                () -> new AuditedEvent(" ", "actor", START, "1"),
                () -> new AuditedEvent("type", null, START, "1"),
                () -> new AuditedEvent("type", " ", START, "1"),
                () -> new AuditedEvent("type", "actor", START, null),
                () -> new AuditedEvent("type", "actor", START, " "))) {
            assertThrows(IllegalArgumentException.class, invalid::create);
        }
        assertThrows(NullPointerException.class, () -> new AuditedEvent("type", "actor", null, "1"));
        assertThrows(IllegalArgumentException.class,
                () -> new HypothesisValidationPort.ValidationAssessment(null, "rationale"));
        assertThrows(IllegalArgumentException.class,
                () -> new HypothesisValidationPort.ValidationAssessment(ValidationOutcome.VALIDATED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HypothesisValidationPort.ValidationAssessment(ValidationOutcome.VALIDATED, " "));
    }

    @Test
    void validatesEveryTelemetryDeltaBoundaryAndItsMean() {
        var measured = validDelta("measured", null);
        assertEquals(new BigDecimal("5.000000"), measured.meanExecutionMillis());
        assertNull(zeroCallsDelta().meanExecutionMillis());
        assertDoesNotThrow(() -> validDelta("partial", "PARTIAL_SOURCE"));
        for (Consumer<MutableDelta> invalid : List.<Consumer<MutableDelta>>of(
                delta -> delta.start = null, delta -> delta.end = null, delta -> delta.executionMillis = null,
                delta -> delta.quality = null, delta -> delta.end = START, delta -> delta.calls = -1,
                delta -> delta.executionMillis = BigDecimal.valueOf(-1), delta -> delta.rows = -1,
                delta -> delta.sharedHits = -1, delta -> delta.sharedReads = -1, delta -> delta.tempReads = -1,
                delta -> delta.tempWrites = -1, delta -> delta.quality = "unknown")) {
            assertThrows(RuntimeException.class, () -> copyDelta(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> validDelta("measured", "reason"));
        assertThrows(IllegalArgumentException.class, () -> validDelta("partial", null));
        assertThrows(IllegalArgumentException.class, () -> validDelta("partial", " "));
    }

    @Test
    void validatesActivityAndHumanDecisionCommands() {
        assertDoesNotThrow(() -> new DatabaseActivitySnapshot(START, 0, 0, 0, true));
        assertThrows(NullPointerException.class, () -> new DatabaseActivitySnapshot(null, 0, 0, 0, true));
        for (DatabaseActivityFactory invalid : List.<DatabaseActivityFactory>of(
                () -> new DatabaseActivitySnapshot(START, -1, 0, 0, true),
                () -> new DatabaseActivitySnapshot(START, 0, -1, 0, true),
                () -> new DatabaseActivitySnapshot(START, 0, 0, -1, true))) {
            assertThrows(IllegalArgumentException.class, invalid::create);
        }
        assertEquals("valid reason", new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE,
                " valid reason ", "reviewer").reason());
        assertThrows(NullPointerException.class,
                () -> new RecordHumanDecisionCommand(null, HumanDecision.APPROVE, "valid reason", "reviewer"));
        assertThrows(NullPointerException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), null, "valid reason", "reviewer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, null, "reviewer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "short", "reviewer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "valid reason", null));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "valid reason", " "));
    }

    private static AggregatedTelemetryDelta validDelta(String quality, String reason) {
        return new AggregatedTelemetryDelta(START, START.plusSeconds(1), 2, BigDecimal.TEN,
                4, 3, 2, 1, 0, quality, reason);
    }

    private static AggregatedTelemetryDelta zeroCallsDelta() {
        return new AggregatedTelemetryDelta(START, START.plusSeconds(1), 0, BigDecimal.ZERO,
                0, 0, 0, 0, 0, "measured", null);
    }

    private static AggregatedTelemetryDelta copyDelta(Consumer<MutableDelta> change) {
        var delta = new MutableDelta();
        change.accept(delta);
        return delta.build();
    }

    private static final class MutableDelta {
        private Instant start = START;
        private Instant end = START.plusSeconds(1);
        private long calls = 1;
        private BigDecimal executionMillis = BigDecimal.ONE;
        private long rows = 1;
        private long sharedHits = 1;
        private long sharedReads = 1;
        private long tempReads = 0;
        private long tempWrites = 0;
        private String quality = "measured";

        private AggregatedTelemetryDelta build() {
            return new AggregatedTelemetryDelta(start, end, calls, executionMillis, rows, sharedHits,
                    sharedReads, tempReads, tempWrites, quality, null);
        }
    }

    @FunctionalInterface interface InferenceRequestFactory { InferencePort.InferenceRequest create(); }
    @FunctionalInterface interface InferenceResponseFactory { InferencePort.InferenceResponse create(); }
    @FunctionalInterface interface AuditedEventFactory { AuditedEvent create(); }
    @FunctionalInterface interface DatabaseActivityFactory { DatabaseActivitySnapshot create(); }
}
