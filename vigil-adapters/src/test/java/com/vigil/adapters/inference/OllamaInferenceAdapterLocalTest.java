package com.vigil.adapters.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.InferencePort;
import java.net.URI;
import java.time.Duration;
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

        var response = adapter.infer(new InferencePort.InferenceRequest(model,
                "Responda somente com a palavra OK.", Duration.ofSeconds(60)));

        assertFalse(response.content().isBlank());
        assertTrue(response.inputTokens() >= 0);
        assertTrue(response.outputTokens() >= 0);
    }
}
