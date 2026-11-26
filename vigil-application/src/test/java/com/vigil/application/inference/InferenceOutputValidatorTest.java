package com.vigil.application.inference;

import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InferenceOutputValidatorTest {
    private static final UUID CASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVIDENCE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void rejectsUnknownEvidenceSchemaAndContentWithoutChangingCaseState() {
        var analysisCase = sanitizedCase();
        var request = request();
        var validator = new InferenceOutputValidator();
        var unknown = output(InferencePort.OutputSchema.DIAGNOSIS_V1,
                Set.of(UUID.fromString("99999999-9999-9999-9999-999999999999")), "Explicação segura");
        var wrongSchema = output(InferencePort.OutputSchema.INDEX_HYPOTHESIS_V1, Set.of(EVIDENCE), "Explicação segura");
        var leaked = output(InferencePort.OutputSchema.DIAGNOSIS_V1, Set.of(EVIDENCE), "SELECT secreto");

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> validator.validate(request, unknown)),
                () -> assertThrows(IllegalArgumentException.class, () -> validator.validate(request, wrongSchema)),
                () -> assertThrows(IllegalArgumentException.class, () -> validator.validate(request, leaked)),
                () -> assertEquals(CaseStatus.SANITIZED, analysisCase.status()),
                () -> assertEquals(3, analysisCase.events().size())
        );
    }

    @Test
    void acceptsOnlyGroundedOutputWithinTheRequestedContract() {
        assertEquals(Set.of(EVIDENCE), new InferenceOutputValidator()
                .validate(request(), output(InferencePort.OutputSchema.DIAGNOSIS_V1,
                        Set.of(EVIDENCE), "Explicação segura e fundamentada")).evidenceIds());
    }

    private static InferencePort.InferenceRequest request() {
        var context = new SanitizedInferenceContext(
                new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                List.of(new SafeRelationReference("relation_orders")), List.of(), Set.of(EVIDENCE));
        return new InferencePort.InferenceRequest("local-model", context, InferencePort.OutputSchema.DIAGNOSIS_V1,
                PromptTemplateVersion.DIAGNOSTIC_V1, Duration.ofSeconds(1));
    }

    private static InferencePort.GroundedOutput output(InferencePort.OutputSchema schema, Set<UUID> evidence,
                                                       String explanation) {
        return new InferencePort.GroundedOutput(schema, explanation, evidence, Optional.empty(), List.of());
    }

    private static AnalysisCase sanitizedCase() {
        Instant now = Instant.parse("2026-11-26T12:00:00Z");
        var result = AnalysisCase.discover(CASE, now);
        result.triage(new TransitionMetadata("test", "triagem", now));
        var payload = new MetricEvidencePayload(MetricKind.CALLS, BigDecimal.ONE, MeasurementUnit.EXECUTIONS,
                new ObservationWindow(now.minusSeconds(60), now));
        result.collectEvidence(new Evidence(EVIDENCE, CASE, payload.type(), EvidenceSource.PG_STAT_STATEMENTS,
                now, "test", EvidenceConfidence.HIGH, payload, Optional.empty()), "test", now);
        result.sanitize(new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                new TransitionMetadata("test", "sanitizado", now));
        return result;
    }
}
