package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.sqlite.SqliteStorageProbe;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.health.HealthStatus;
import com.vigil.application.port.out.interfaces.Clock;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

public class Main {

    private static final String APPLICATION_VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        System.exit(VigilCli.run(args, System.getenv(), System.out, System.err));
    }

    static int run(Map<String, String> environmentVariables, PrintStream output, PrintStream errors) {
        final StorageConfiguration storageConfiguration;
        try {
            storageConfiguration = storageConfiguration(environmentVariables);
            // Validate every explicit override before touching storage. Omitted values come from the active profile.
            TargetProfileLoader.validateOverrides(environmentVariables);
        } catch (RuntimeException exception) {
            errors.println("Falha na configuração do Vigil: " + exception.getMessage());
            return 2;
        }

        try {
            // A read-only preflight preserves detailed storage diagnostics without creating SQLite.
            HealthStatus healthStatus = new CheckVigilHealth(new SystemClockAdapter(), APPLICATION_VERSION,
                    new SqliteStorageProbe(), storageConfiguration)
                    .check(new VigilConfiguration("local", "local"));
            if (!healthStatus.healthy()) {
                output.println(healthStatus);
                healthStatus.storage().problems().forEach(errors::println);
                return 1;
            }
            try (var context = VigilContext.open(environmentVariables)) {
                // Context startup includes the SQLite migrations. Report success only afterwards.
                healthStatus = context.getBean(CheckVigilHealth.class)
                        .check(context.getBean(VigilConfiguration.class));
                output.println(healthStatus);
            }
            return 0;
        } catch (RuntimeException exception) {
            errors.println("Falha ao verificar armazenamento em " + storageConfiguration.databaseFile()
                    + ". Verifique o volume e a integridade do banco.");
            return 1;
        }
    }

    static StorageConfiguration storageConfiguration(Map<String, String> environmentVariables) {
        String databasePath = environmentVariables.get("VIGIL_DATABASE_PATH");
        if (databasePath == null || databasePath.isBlank()) {
            throw new IllegalArgumentException("VIGIL_DATABASE_PATH é obrigatória. Informe o caminho absoluto do volume SQLite.");
        }
        String configuredMinimum = environmentVariables.get("VIGIL_STORAGE_MIN_FREE_BYTES");
        long minimum = 268_435_456L;
        if (configuredMinimum != null && !configuredMinimum.isBlank()) {
            try {
                minimum = Long.parseLong(configuredMinimum);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("VIGIL_STORAGE_MIN_FREE_BYTES deve ser um número inteiro positivo.");
            }
        }
        if (minimum <= 0) {
            throw new IllegalArgumentException("VIGIL_STORAGE_MIN_FREE_BYTES deve ser um número inteiro positivo.");
        }
        final Path path;
        try {
            path = Path.of(databasePath);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("VIGIL_DATABASE_PATH deve ser um caminho absoluto válido.");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("VIGIL_DATABASE_PATH deve ser um caminho absoluto válido.");
        }
        return new StorageConfiguration(path, minimum);
    }
}
