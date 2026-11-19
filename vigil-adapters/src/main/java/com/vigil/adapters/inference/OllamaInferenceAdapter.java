package com.vigil.adapters.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigil.application.port.out.interfaces.InferencePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Local Ollama adapter. It sends only the caller-approved prompt to /api/generate. */
public final class OllamaInferenceAdapter implements InferencePort {
    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public OllamaInferenceAdapter(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    OllamaInferenceAdapter(URI endpoint, HttpClient client, ObjectMapper mapper) {
        this.endpoint = Objects.requireNonNull(endpoint, "Endpoint must not be null.");
        this.client = Objects.requireNonNull(client, "HTTP client must not be null.");
        this.mapper = Objects.requireNonNull(mapper, "Object mapper must not be null.");
    }

    @Override
    public InferenceResponse infer(InferenceRequest request) {
        try {
            String body = mapper.writeValueAsString(Map.of("model", request.model(), "prompt", request.prompt(), "stream", false));
            HttpRequest http = HttpRequest.newBuilder(endpoint.resolve("/api/generate"))
                    .timeout(request.timeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("response").asText();
            if (content.isBlank()) throw new IllegalStateException("Ollama returned an empty response.");
            return new InferenceResponse(root.path("model").asText(request.model()), content,
                    root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama inference interrupted.", interrupted);
        } catch (IOException exception) {
            throw new IllegalStateException("Ollama local inference unavailable.", exception);
        }
    }
}
