package com.vigil.adapters.inference;

import com.vigil.application.inference.PromptTemplateVersion;
import com.vigil.application.inference.SanitizedInferenceContext;
import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInferenceAdapterTest {
    @Test
    void disabledAdapterNeverInvokesTheClient() {
        var calls = new AtomicInteger();
        var adapter = new BedrockInferenceAdapter(request -> { calls.incrementAndGet(); return response(request); }, false);
        assertThrows(IllegalStateException.class, () -> adapter.infer(request()));
        assertEquals(0, calls.get());
    }

    @Test
    void enabledAdapterUsesTheProviderIndependentContractWithAFakeClient() {
        var calls = new AtomicInteger();
        var adapter = new BedrockInferenceAdapter(request -> { calls.incrementAndGet(); return response(request); }, true);
        assertEquals("bedrock-fake", adapter.infer(request()).model());
        assertEquals(1, calls.get());
    }

    private static InferencePort.InferenceRequest request() {
        var context = new SanitizedInferenceContext(
                new SanitizationResult.Safe("query family sanitized", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                List.of(new SafeRelationReference("relation_orders")),
                List.of(new SafeCatalogReference("column_11111111111111111111111111111111")),
                Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111")));
        return new InferencePort.InferenceRequest("anthropic.fake", context,
                InferencePort.OutputSchema.DIAGNOSIS_V1, PromptTemplateVersion.DIAGNOSTIC_V1, Duration.ofSeconds(2));
    }

    private static InferencePort.InferenceResponse response(InferencePort.InferenceRequest request) {
        var output = new InferencePort.GroundedOutput(InferencePort.OutputSchema.DIAGNOSIS_V1,
                "Explicação segura e fundamentada", request.context().allowedEvidenceIds(), Optional.empty(), List.of());
        return new InferencePort.InferenceResponse("bedrock-fake", output, 1, 1, Duration.ofMillis(1));
    }
}
