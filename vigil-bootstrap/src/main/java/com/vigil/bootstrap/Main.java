package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.sqlite.SqliteStorageProbe;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
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
            TargetProfile targetProfile = TargetProfileLoader.load(environmentVariables);
            vigilConfiguration = new VigilConfiguration(targetProfile.name(), targetProfile.environment());
        } catch (RuntimeException exception) {
            errors.println("Falha na configuração do Vigil: " + exception.getMessage());
            return 2;
        }

        try {
            Clock clock = new SystemClockAdapter();
            HealthStatus healthStatus = new CheckVigilHealth(clock, APPLICATION_VERSION,
                    new SqliteStorageProbe(), storageConfiguration).check(vigilConfiguration);
            output.println(healthStatus);
            if (!healthStatus.healthy()) {
                healthStatus.storage().problems().forEach(errors::println);
                return 1;
            }
            try (var context = VigilContext.open(environmentVariables)) {
                context.getBean(CheckVigilHealth.class);
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
