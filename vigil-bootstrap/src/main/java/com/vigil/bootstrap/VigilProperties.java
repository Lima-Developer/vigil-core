package com.vigil.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed external settings; validated by the application's own configuration value objects. */
@ConfigurationProperties(prefix = "vigil")
public record VigilProperties(Target target, Sqlite sqlite, Inference inference) {
    public record Target(String name, String environment, String host, Integer port, String database,
                         String sslMode, Duration connectTimeout) { }
    public record Sqlite(Path path, Long minimumFreeBytes) { }
    public record Inference(Boolean enabled, String model, Duration timeout) { }
}
