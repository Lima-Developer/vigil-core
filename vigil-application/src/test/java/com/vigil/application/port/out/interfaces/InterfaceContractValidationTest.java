package com.vigil.application.port.out.interfaces;

import com.vigil.application.casework.HumanDecision;
import com.vigil.application.casework.RecordHumanDecisionCommand;
import com.vigil.application.telemetry.AggregatedTelemetryDelta;
import com.vigil.core.domain.enums.ValidationOutcome;
import com.vigil.application.inference.PromptTemplateVersion;
import com.vigil.application.inference.SanitizedInferenceContext;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
        assertDoesNotThrow(() -> request("model", validContext(), Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> new InferencePort.InferenceResponse("model", validOutput(), 0, 0, Duration.ZERO));
        for (InferenceRequestFactory invalid : List.<InferenceRequestFactory>of(
                () -> request(null, validContext(), Duration.ofSeconds(1)),
                () -> request(" ", validContext(), Duration.ofSeconds(1)),
                () -> request("model", null, Duration.ofSeconds(1)),
                () -> request("model", validContext(), null),
                () -> request("model", validContext(), Duration.ZERO),
                () -> request("model", validContext(), Duration.ofMillis(-1)))) {
            assertThrows(RuntimeException.class, invalid::create);
        }
        for (InferenceResponseFactory invalid : List.<InferenceResponseFactory>of(
                () -> new InferencePort.InferenceResponse(null, validOutput(), 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse(" ", validOutput(), 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", null, 0, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", validOutput(), -1, 0, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", validOutput(), 0, -1, Duration.ZERO),
                () -> new InferencePort.InferenceResponse("model", validOutput(), 0, 0, null),
                () -> new InferencePort.InferenceResponse("model", validOutput(), 0, 0, Duration.ofMillis(-1)))) {
            assertThrows(RuntimeException.class, invalid::create);
        }
    }

    private static InferencePort.InferenceRequest request(String model, SanitizedInferenceContext context,
                                                          Duration timeout) {
        return new InferencePort.InferenceRequest(model, context, InferencePort.OutputSchema.DIAGNOSIS_V1,
                PromptTemplateVersion.DIAGNOSTIC_V1, timeout);
    }

    private static SanitizedInferenceContext validContext() {
        return new SanitizedInferenceContext(
                new SanitizationResult.Safe("sanitized", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                List.of(new SafeRelationReference("relation_orders")), List.of(),
                Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111")));
    }

    private static InferencePort.GroundedOutput validOutput() {
        return new InferencePort.GroundedOutput(InferencePort.OutputSchema.DIAGNOSIS_V1,
                "Explicação segura", validContext().allowedEvidenceIds(), Optional.empty(), List.of());
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
                " valid reason ", "reviewer", 1).reason());
        assertThrows(NullPointerException.class,
                () -> new RecordHumanDecisionCommand(null, HumanDecision.APPROVE, "valid reason", "reviewer", 1));
        assertThrows(NullPointerException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), null, "valid reason", "reviewer", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, null, "reviewer", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "short", "reviewer", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "valid reason", null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE, "valid reason", " ", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordHumanDecisionCommand(java.util.UUID.randomUUID(), HumanDecision.APPROVE,
                        "valid reason", "reviewer", 0));
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
