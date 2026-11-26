package com.vigil.adapters.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.application.inference.PromptTemplateVersion;
import com.vigil.application.inference.SanitizedInferenceContext;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in smoke test for a locally installed Ollama runtime. It never sends SQL,
 * credentials, target metadata, or business data.
 */
@EnabledIfSystemProperty(named = "vigil.ollama.e2e", matches = "true")
@EnabledIfEnvironmentVariable(named = "VIGIL_OLLAMA_MODEL", matches = ".+")
class OllamaInferenceAdapterLocalTest {
    @Test
    void evaluatesTheConfiguredLocalModelThroughTheRealOllamaRuntime() {
        var model = System.getenv("VIGIL_OLLAMA_MODEL");
        var endpoint = System.getenv().getOrDefault("VIGIL_INFERENCE_ENDPOINT", "http://127.0.0.1:11434");
        var adapter = new OllamaInferenceAdapter(URI.create(endpoint));

        var context = new SanitizedInferenceContext(
                new SanitizationResult.Safe("query family sanitized", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                List.of(new SafeRelationReference("relation_synthetic")), List.of(),
                Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111")));
        var response = adapter.infer(new InferencePort.InferenceRequest(model, context,
                InferencePort.OutputSchema.DIAGNOSIS_V1, PromptTemplateVersion.DIAGNOSTIC_V1,
                Duration.ofSeconds(60)));

        assertFalse(response.output().explanation().isBlank());
        assertTrue(response.inputTokens() >= 0);
        assertTrue(response.outputTokens() >= 0);
    }
}
