package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.sqlite.SqliteStorageProbe;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.health.HealthStatus;
import com.vigil.application.port.out.interfaces.Clock;

import java.nio.file.Path;

public class Main {

    private static final String APPLICATION_VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        try {
            String targetName = System.getenv("VIGIL_TARGET_NAME");
            String environment = System.getenv("VIGIL_ENVIRONMENT");
            String databasePath = System.getenv("VIGIL_DATABASE_PATH");
            if (databasePath == null || databasePath.isBlank()) {
                throw new IllegalArgumentException("VIGIL_DATABASE_PATH é obrigatória. Informe o caminho absoluto do volume SQLite.");
            }
            String configuredMinimum = System.getenv("VIGIL_STORAGE_MIN_FREE_BYTES");
            long minimum = configuredMinimum == null || configuredMinimum.isBlank()
                    ? 268_435_456L : Long.parseLong(configuredMinimum);
            StorageConfiguration storageConfiguration = new StorageConfiguration(Path.of(databasePath), minimum);

            VigilConfiguration vigilConfiguration = new VigilConfiguration(targetName, environment);

            Clock clock = new SystemClockAdapter();

            CheckVigilHealth checkVigilHealth = new CheckVigilHealth(clock, APPLICATION_VERSION,
                    new SqliteStorageProbe(), storageConfiguration);

            HealthStatus healthStatus = checkVigilHealth.check(vigilConfiguration);

            System.out.println(healthStatus);
            if (!healthStatus.healthy()) {
                healthStatus.storage().problems().forEach(System.err::println);
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Falha na configuração do Vigil: " + e.getMessage());
            System.exit(2);
        }
    }
}
