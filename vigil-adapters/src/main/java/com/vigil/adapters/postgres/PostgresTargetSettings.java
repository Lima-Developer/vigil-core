package com.vigil.adapters.postgres;

import com.vigil.application.configuration.TargetProfile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Connection settings of the single monitored target. The password is deliberately not part of this record; it is
 * supplied separately to {@link PostgresConnectionFactory} so it can never reach {@code toString} or a log line.
 */
record PostgresTargetSettings(
        TargetProfile profile,
        String user,
        Path sslRootCert,
        Duration socketTimeout,
        Duration statementTimeout,
        Duration lockTimeout
) {
    static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMillis(200);

    PostgresTargetSettings {
        Objects.requireNonNull(profile, "Perfil do target não pode ser nulo");
        Objects.requireNonNull(user, "Usuário do target não pode ser nulo");
        Objects.requireNonNull(socketTimeout, "Socket timeout não pode ser nulo");
        Objects.requireNonNull(statementTimeout, "Statement timeout não pode ser nulo");
        Objects.requireNonNull(lockTimeout, "Lock timeout não pode ser nulo");
        if (user.isBlank()) {
            throw new IllegalArgumentException("Usuário do target não pode ser vazio");
        }
        requirePositiveMillis(socketTimeout, "Socket timeout");
        requirePositiveMillis(statementTimeout, "Statement timeout");
        requirePositiveMillis(lockTimeout, "Lock timeout");
        if (socketTimeout.compareTo(statementTimeout) <= 0) {
            throw new IllegalArgumentException("Socket timeout deve ser maior que o statement timeout");
        }
    }

    static PostgresTargetSettings withDefaults(TargetProfile profile, String user, Path sslRootCert) {
        return new PostgresTargetSettings(
                profile, user, sslRootCert, DEFAULT_SOCKET_TIMEOUT, DEFAULT_STATEMENT_TIMEOUT, DEFAULT_LOCK_TIMEOUT);
    }

    private static void requirePositiveMillis(Duration timeout, String label) {
        if (timeout.toMillis() <= 0) {
            throw new IllegalArgumentException(label + " deve ser de pelo menos 1 ms");
        }
    }

    Optional<Path> optionalSslRootCert() {
        return Optional.ofNullable(sslRootCert);
    }

    /** JDBC URL built only from fields already validated by {@link TargetProfile}. */
    String jdbcUrl() {
        return "jdbc:postgresql://" + profile.host() + ":" + profile.port() + "/" + profile.database();
    }

    /** Startup options applied by the server to every transaction of the session. */
    String sessionOptions() {
        return "-c statement_timeout=" + statementTimeout.toMillis()
                + " -c lock_timeout=" + lockTimeout.toMillis()
                + " -c default_transaction_read_only=on";
    }

    String expectedStatementTimeout() {
        return serverFormat(statementTimeout.toMillis());
    }

    String expectedLockTimeout() {
        return serverFormat(lockTimeout.toMillis());
    }

    /** Mirrors how PostgreSQL prints a millisecond setting in {@code SHOW}: the largest exact unit. */
    static String serverFormat(long millis) {
        if (millis % 86_400_000L == 0) {
            return millis / 86_400_000L + "d";
        }
        if (millis % 3_600_000L == 0) {
            return millis / 3_600_000L + "h";
        }
        if (millis % 60_000L == 0) {
            return millis / 60_000L + "min";
        }
        if (millis % 1_000L == 0) {
            return millis / 1_000L + "s";
        }
        return millis + "ms";
    }

    @Override
    public String toString() {
        return "PostgresTargetSettings[profile=" + profile + ", user=" + user
                + ", socketTimeout=" + socketTimeout + ", statementTimeout=" + statementTimeout
                + ", lockTimeout=" + lockTimeout + "]";
    }
}
