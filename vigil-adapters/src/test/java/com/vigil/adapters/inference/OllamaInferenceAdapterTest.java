package com.vigil.adapters.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.application.inference.PromptTemplateVersion;
import com.vigil.application.inference.SanitizedInferenceContext;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OllamaInferenceAdapterTest {
    @Test
    void sendsTheApprovedPromptToTheLocalOllamaContractAndPreservesUsage() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "{\"model\":\"qwen3:8b\",\"response\":\"{\\\"schema\\\":\\\"DIAGNOSIS_V1\\\","
                    + "\\\"explanation\\\":\\\"Explicação segura e fundamentada\\\","
                    + "\\\"evidenceIds\\\":[\\\"11111111-1111-1111-1111-111111111111\\\"],"
                    + "\\\"relation\\\":null,\\\"columns\\\":[]}\","
                    + "\"prompt_eval_count\":17,\"eval_count\":9}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var adapter = new OllamaInferenceAdapter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            var context = new SanitizedInferenceContext(
                    new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                    List.of(new SafeRelationReference("relation_orders")),
                    List.of(new SafeCatalogReference("column_11111111111111111111111111111111")),
                    Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111")));
            var response = adapter.infer(new InferencePort.InferenceRequest("qwen3:8b", context,
                    InferencePort.OutputSchema.DIAGNOSIS_V1, PromptTemplateVersion.DIAGNOSTIC_V1,
                    Duration.ofSeconds(2)));

            assertEquals("qwen3:8b", response.model());
            assertEquals("Explicação segura e fundamentada", response.output().explanation());
            assertEquals(17, response.inputTokens());
            assertEquals(9, response.outputTokens());
            assertTrue(received.get().contains("template=DIAGNOSTIC_V1"));
            assertTrue(received.get().contains("11111111-1111-1111-1111-111111111111"));
            assertTrue(!received.get().contains("SELECT $1"));
            assertTrue(received.get().contains("\"stream\":false"));
        } finally {
            server.stop(0);
        }
    }
}
