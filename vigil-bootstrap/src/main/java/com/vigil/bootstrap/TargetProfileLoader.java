package com.vigil.bootstrap;

import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

final class TargetProfileLoader {

    private static final String NAME = "VIGIL_TARGET_NAME";
    private static final String ENVIRONMENT = "VIGIL_ENVIRONMENT";
    private static final String HOST = "VIGIL_TARGET_HOST";
    private static final String PORT = "VIGIL_TARGET_PORT";
    private static final String DATABASE = "VIGIL_TARGET_DATABASE";
    private static final String SSL_MODE = "VIGIL_TARGET_SSL_MODE";
    private static final String CONNECT_TIMEOUT = "VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS";
    private static final String TARGETS = "VIGIL_TARGETS";
    private static final Pattern INDEXED_TARGET = Pattern.compile("^VIGIL_TARGET_\\d+_.*");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,9}");
    private static final String DEFAULT_SSL_MODE = "verify-full";
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

    private TargetProfileLoader() {
    }

    static TargetProfile load(Map<String, String> environmentVariables) {
        rejectMultipleTargets(environmentVariables);
        String name = required(environmentVariables, NAME);
        String environment = required(environmentVariables, ENVIRONMENT);
        String host = required(environmentVariables, HOST);
        String database = required(environmentVariables, DATABASE);
        int port = integer(environmentVariables, PORT, TargetProfile.DEFAULT_PORT);
        int timeoutSeconds = integer(environmentVariables, CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_SECONDS);
        TargetSslMode sslMode = sslMode(environmentVariables);
        return build(name, environment, host, port, database, sslMode, timeoutSeconds);
    }

    private static TargetProfile build(String name, String environment, String host, int port, String database,
                                       TargetSslMode sslMode, int timeoutSeconds) {
        // Nome e ambiente já foram validados como não vazios; cada campo restante é validado
        // isoladamente para que a mensagem nomeie a variável responsável.
        Duration validTimeout = Duration.ofSeconds(1);
        validate(HOST, () -> new TargetProfile(name, environment, host, 1, "d", sslMode, validTimeout));
        validate(PORT, () -> new TargetProfile(name, environment, "h", port, "d", sslMode, validTimeout));
        validate(DATABASE, () -> new TargetProfile(name, environment, "h", 1, database, sslMode, validTimeout));
        validate(CONNECT_TIMEOUT, () -> new TargetProfile(name, environment, "h", 1, "d", sslMode,
                Duration.ofSeconds(timeoutSeconds)));
        return new TargetProfile(name, environment, host, port, database, sslMode,
                Duration.ofSeconds(timeoutSeconds));
    }

    private static void validate(String variable, Runnable check) {
        try {
            check.run();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(variable + " inválida: " + exception.getMessage());
        }
    }

    static void rejectMultipleTargets(Map<String, String> environmentVariables) {
        environmentVariables.keySet().stream().sorted()
                .filter(key -> TARGETS.equals(key) || INDEXED_TARGET.matcher(key).matches())
                .findFirst()
                .ifPresent(key -> {
                    throw new IllegalArgumentException(key
                            + " não é suportada. O Vigil aceita exatamente um target (VIGIL_TARGET_*).");
                });
    }

    private static String required(Map<String, String> environmentVariables, String variable) {
        String value = environmentVariables.get(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(variable + " é obrigatória e não pode estar em branco.");
        }
        return value;
    }

    private static int integer(Map<String, String> environmentVariables, String variable, int defaultValue) {
        if (!environmentVariables.containsKey(variable)) {
            return defaultValue;
        }
        String value = environmentVariables.get(variable);
        if (value == null || !INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException(variable + " deve ser um número inteiro sem sinal nem fração.");
        }
        return Integer.parseInt(value);
    }

    private static TargetSslMode sslMode(Map<String, String> environmentVariables) {
        if (!environmentVariables.containsKey(SSL_MODE)) {
            return TargetSslMode.fromConfigValue(DEFAULT_SSL_MODE);
        }
        return TargetSslMode.fromConfigValue(environmentVariables.get(SSL_MODE));
    }
}
