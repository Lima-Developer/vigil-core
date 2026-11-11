package com.vigil.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the HTTP binding, use cases and SQLite together, without opening SQLite directly. */
class DemoCaseHttpIT {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void oneCaseCanBeCreatedViewedAndTriagedWithExplicitRejections() throws Exception {
        try (var context = new SpringApplicationBuilder(VigilApplication.class)
                .properties("spring.main.banner-mode=off", "server.port=0", "vigil.target.name=lab",
                        "vigil.target.environment=test", "vigil.target.host=localhost",
                        "vigil.target.database=postgres", "vigil.sqlite.path=" + directory.resolve("case.db"),
                        "vigil.sqlite.minimum-free-bytes=1")
                .run()) {
            var base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                assertEquals(404, request(client, "GET", base + "/api/cases/demo", null).statusCode());
                var created = request(client, "POST", base + "/api/cases/demo", "");
                assertEquals(200, created.statusCode());
                var id = JSON.readTree(created.body()).path("id").asText();
                assertFalse(id.isBlank());
                assertEquals(id, JSON.readTree(request(client, "GET", base + "/api/cases/demo", null).body())
                        .path("id").asText());
                assertEquals(400, request(client, "POST", base + "/api/cases/" + id + "/triage",
                        "{\"reason\":\"curto\"}").statusCode());
                assertEquals(404, request(client, "POST",
                        base + "/api/cases/00000000-0000-0000-0000-000000000001/triage",
                        "{\"reason\":\"motivo suficiente\"}").statusCode());
                var triaged = request(client, "POST", base + "/api/cases/" + id + "/triage",
                        "{\"reason\":\"Consulta selecionada para revisão do plano\"}");
                assertEquals(200, triaged.statusCode());
                assertEquals("TRIAGED", JSON.readTree(triaged.body()).path("status").asText());
                assertTrue(JSON.readTree(triaged.body()).path("events").size() >= 3);
                assertEquals(409, request(client, "POST", base + "/api/cases/" + id + "/triage",
                        "{\"reason\":\"Motivo duplicado\"}").statusCode());
            }
        }
    }

    private static HttpResponse<String> request(HttpClient client, String method, String url, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
