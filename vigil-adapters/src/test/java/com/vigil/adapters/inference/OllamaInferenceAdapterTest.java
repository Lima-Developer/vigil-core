package com.vigil.adapters.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vigil.application.port.out.interfaces.InferencePort;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OllamaInferenceAdapterTest {
    @Test
    void sendsTheApprovedPromptToTheLocalOllamaContractAndPreservesUsage() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "{\"model\":\"qwen3:8b\",\"response\":\"{\\\"finding\\\":\\\"safe\\\"}\","
                    + "\"prompt_eval_count\":17,\"eval_count\":9}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var adapter = new OllamaInferenceAdapter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            var response = adapter.infer(new InferencePort.InferenceRequest("qwen3:8b",
                    "SQL já sanitizado e sem dados do alvo.", Duration.ofSeconds(2)));

            assertEquals("qwen3:8b", response.model());
            assertEquals("{\"finding\":\"safe\"}", response.content());
            assertEquals(17, response.inputTokens());
            assertEquals(9, response.outputTokens());
            assertTrue(received.get().contains("SQL já sanitizado"));
            assertTrue(received.get().contains("\"stream\":false"));
        } finally {
            server.stop(0);
        }
    }
}
