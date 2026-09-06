package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.health.HealthStatus;
import com.vigil.application.port.out.ClockPort;

public class Main {

    private static final String APPLICATION_VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        try {
            String targetName = System.getenv("VIGIL_TARGET_NAME");
            String environment = System.getenv("VIGIL_ENVIRONMENT");
            System.out.printf(
                    "targetName=%s, environment=%s%n",
                    targetName,
                    environment
            );

            VigilConfiguration vigilConfiguration = new VigilConfiguration(targetName, environment);

            ClockPort clock = new SystemClockAdapter();

            CheckVigilHealth checkVigilHealth = new CheckVigilHealth(clock, APPLICATION_VERSION);

            HealthStatus healthStatus = checkVigilHealth.check(vigilConfiguration);

            System.out.println(healthStatus);
        } catch (Exception e) {
            System.err.println("Falha na configuração do Vigil: " + e.getMessage());
            System.exit(2);
        }
    }
}