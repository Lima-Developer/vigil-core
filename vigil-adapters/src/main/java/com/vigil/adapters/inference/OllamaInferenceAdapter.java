package com.vigil.adapters.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.application.inference.InferenceOutputValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
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
            requireApproved(request);
            String body = mapper.writeValueAsString(Map.of("model", request.model(),
                    "prompt", renderPrompt(request), "stream", false));
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
            var output = parseOutput(content);
            new InferenceOutputValidator().validate(request, output);
            return new InferenceResponse(root.path("model").asText(request.model()), output,
                    root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama inference interrupted.", interrupted);
        } catch (IOException exception) {
            throw new IllegalStateException("Ollama local inference unavailable.", exception);
        }
    }

    private static void requireApproved(InferenceRequest request) {
        Objects.requireNonNull(request, "Inference request must not be null.");
        if (request.context().allowedEvidenceIds().isEmpty()) {
            throw new IllegalArgumentException("INFERENCE_CONTEXT_NOT_APPROVED");
        }
    }

    private static String renderPrompt(InferenceRequest request) {
        var context = request.context();
        return "template=" + request.templateVersion().name()
                + "\nschema=" + request.outputSchema().name()
                + "\nrelations=" + String.join(",", context.relations().stream().map(item -> item.value()).toList())
                + "\ncatalog=" + String.join(",", context.catalogReferences().stream().map(item -> item.value()).toList())
                + "\nevidence=" + String.join(",", context.allowedEvidenceIds().stream().map(UUID::toString).sorted().toList())
                + "\nReturn only one JSON object matching the requested schema and citing only listed evidence.";
    }

    private GroundedOutput parseOutput(String content) throws IOException {
        JsonNode output = mapper.readTree(content);
        if (!output.isObject() || !output.path("schema").isTextual() || !output.path("explanation").isTextual()
                || !output.path("evidenceIds").isArray() || !output.path("columns").isArray()) {
            throw new IllegalArgumentException("INFERENCE_OUTPUT_SCHEMA_INVALID");
        }
        OutputSchema schema;
        try {
            schema = OutputSchema.valueOf(output.path("schema").textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INFERENCE_OUTPUT_SCHEMA_INVALID");
        }
        var evidence = new java.util.LinkedHashSet<UUID>();
        output.path("evidenceIds").forEach(item -> evidence.add(UUID.fromString(item.asText())));
        var columns = new java.util.ArrayList<com.vigil.core.domain.SafeCatalogReference>();
        output.path("columns").forEach(item -> columns.add(new com.vigil.core.domain.SafeCatalogReference(item.asText())));
        Optional<com.vigil.core.domain.SafeRelationReference> relation = output.path("relation").isTextual()
                ? Optional.of(new com.vigil.core.domain.SafeRelationReference(output.path("relation").textValue()))
                : Optional.empty();
        return new GroundedOutput(schema, output.path("explanation").textValue(), evidence, relation, columns);
    }
}
