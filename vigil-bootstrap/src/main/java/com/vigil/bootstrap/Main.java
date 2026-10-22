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
        final VigilConfiguration vigilConfiguration;
        try {
            storageConfiguration = storageConfiguration(environmentVariables);
            vigilConfiguration = new VigilConfiguration(environmentVariables.get("VIGIL_TARGET_NAME"),
                    environmentVariables.get("VIGIL_ENVIRONMENT"));
        } catch (RuntimeException exception) {
            errors.println("Falha na configuração do Vigil: " + exception.getMessage());
            return 2;
        }

        try {
            Clock clock = new SystemClockAdapter();
            CheckVigilHealth checkVigilHealth = new CheckVigilHealth(clock, APPLICATION_VERSION,
                    new SqliteStorageProbe(), storageConfiguration);
            HealthStatus healthStatus = checkVigilHealth.check(vigilConfiguration);
            output.println(healthStatus);
            if (!healthStatus.healthy()) {
                healthStatus.storage().problems().forEach(errors::println);
                return 1;
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
        return new StorageConfiguration(Path.of(databasePath), minimum);
    }
}
