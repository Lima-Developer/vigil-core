package com.vigil.bootstrap;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed external settings; validated by the application's own configuration value objects. */
@ConfigurationProperties(prefix = "vigil")
public record VigilProperties(Target target, Sqlite sqlite, Inference inference, Validation validation, Demo demo, Telemetry telemetry) {
    public record Target(String name, String environment, String host, String port, String database,
                         String sslMode, Duration connectTimeout) { }
    public record Sqlite(Path path, Long minimumFreeBytes) { }
    public record Inference(Boolean enabled, String model, Duration timeout, URI endpoint) {
        public Inference {
            if (Boolean.TRUE.equals(enabled) && (model == null || model.isBlank())) {
                throw new IllegalArgumentException("vigil.inference.model é obrigatório quando inferência está ativa");
            }
            if (timeout != null && (timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofMinutes(1)) > 0)) {
                throw new IllegalArgumentException("vigil.inference.timeout deve estar entre 1 segundo e 1 minuto");
            }
        }
    }
    public record Validation(Path artifactPath) { }
    public record Demo(String select, String postgresUser, Path sslRootCert) { }
    public record Telemetry(Boolean enabled, String postgresUser, String sslRootCert, Duration interval,
                            Duration retention, Integer maximumSamples) { }
}
