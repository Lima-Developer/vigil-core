package com.vigil.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/** Allocates storage only for an explicitly requested local web demonstration. */
final class EphemeralDemoStorage implements AutoCloseable {
    private static final String DEMO_FLAG = "--vigil.demo.ephemeral=true";
    private final Path directory;
    private final Path databaseFile;

    private EphemeralDemoStorage(Path directory) {
        this.directory = directory;
        this.databaseFile = directory.resolve("vigil-demo.db");
    }

    static Optional<EphemeralDemoStorage> prepare(String[] arguments, Map<String, String> environment) {
        if (!requested(arguments, environment) || configuredDatabase(arguments, environment)) return Optional.empty();
        try {
            return Optional.of(new EphemeralDemoStorage(Files.createTempDirectory("vigil-demo-")));
        } catch (IOException failure) {
            throw new IllegalStateException("Não foi possível preparar o armazenamento temporário da demonstração.", failure);
        }
    }

    Path databaseFile() {
        return databaseFile;
    }

    @Override
    public void close() {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Shutdown cleanup is best-effort; no user database is ever allocated here.
                }
            });
        } catch (IOException ignored) {
            // The directory belongs to the operating-system temporary area.
        }
    }

    private static boolean requested(String[] arguments, Map<String, String> environment) {
        return "true".equalsIgnoreCase(environment.get("VIGIL_DEMO_MODE"))
                || java.util.Arrays.stream(arguments).anyMatch(EphemeralDemoStorage::isDemoArgument);
    }

    private static boolean isDemoArgument(String argument) {
        return DEMO_FLAG.equals(argument)
                || argument.startsWith("--spring.profiles.active=")
                && java.util.Arrays.stream(argument.substring(argument.indexOf('=') + 1).split(","))
                .map(String::trim).anyMatch("demo"::equals);
    }

    private static boolean configuredDatabase(String[] arguments, Map<String, String> environment) {
        return environment.containsKey("VIGIL_DATABASE_PATH") && !environment.get("VIGIL_DATABASE_PATH").isBlank()
                || java.util.Arrays.stream(arguments).anyMatch(argument -> argument.startsWith("--vigil.sqlite.path="));
    }
}
