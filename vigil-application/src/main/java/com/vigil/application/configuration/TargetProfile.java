package com.vigil.application.configuration;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record TargetProfile(
        String name,
        String environment,
        String host,
        int port,
        String database,
        TargetSslMode sslMode,
        Duration connectTimeout
) {
    public static final int DEFAULT_PORT = 5432;

    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,63}");
    private static final long MIN_TIMEOUT_SECONDS = 1;
    private static final long MAX_TIMEOUT_SECONDS = 60;

    public TargetProfile {
        Objects.requireNonNull(name, "Nome do target não pode ser nulo");
        Objects.requireNonNull(environment, "Ambiente do target não pode ser nulo");
        Objects.requireNonNull(host, "Host do target não pode ser nulo");
        Objects.requireNonNull(database, "Database do target não pode ser nulo");
        Objects.requireNonNull(sslMode, "Modo SSL do target não pode ser nulo");
        Objects.requireNonNull(connectTimeout, "Timeout de conexão do target não pode ser nulo");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Nome do target não pode ser vazio");
        }
        if (environment.isBlank()) {
            throw new IllegalArgumentException("Ambiente do target não pode ser vazio");
        }
        if (host.length() > MAX_HOST_LENGTH || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "Host do target deve ter de 1 a 253 caracteres entre letras, dígitos, '.' e '-'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Porta do target deve estar entre 1 e 65535");
        }
        if (!DATABASE_PATTERN.matcher(database).matches()) {
            throw new IllegalArgumentException(
                    "Database do target deve ter de 1 a 63 caracteres entre letras, dígitos, '_' e '-'");
        }
        long seconds = connectTimeout.getSeconds();
        if (connectTimeout.getNano() != 0 || seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "Timeout de conexão do target deve ser um número inteiro de segundos entre 1 e 60");
        }
    }

    @Override
    public String toString() {
        return "TargetProfile[name=" + name + ", environment=" + environment + ", host=" + host
                + ", port=" + port + ", database=" + database + ", sslMode=" + sslMode.configValue() + "]";
    }
}
