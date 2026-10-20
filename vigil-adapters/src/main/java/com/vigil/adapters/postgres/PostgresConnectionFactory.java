package com.vigil.adapters.postgres;

import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.exception.TargetFailureReason;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Opens least-privilege connections to the monitored target: verified TLS, read-only and time-limited sessions,
 * each setting confirmed with {@code SHOW} before the connection is returned. Only initial connection failures that
 * look transient are retried; every other failure is raised at once.
 * <p>
 * The {@link Connection} never leaves the adapter: the class is package-private and every failure that escapes is a
 * {@link TargetConnectionFailure}, which carries neither URL nor credentials. The caller owns and must close each
 * returned connection.
 */
final class PostgresConnectionFactory {
    static final List<Duration> RETRY_BACKOFFS = List.of(Duration.ofMillis(200), Duration.ofMillis(800));
    static final String APPLICATION_NAME = "vigil";

    private final PostgresTargetSettings settings;
    private final Supplier<char[]> password;
    private final ConnectionOpener opener;
    private final Sleeper sleeper;

    PostgresConnectionFactory(PostgresTargetSettings settings, Supplier<char[]> password) {
        this(settings, password, DriverManager::getConnection, duration -> Thread.sleep(duration.toMillis()));
    }

    PostgresConnectionFactory(
            PostgresTargetSettings settings, Supplier<char[]> password, ConnectionOpener opener, Sleeper sleeper) {
        this.settings = Objects.requireNonNull(settings, "Settings must not be null.");
        this.password = Objects.requireNonNull(password, "Password supplier must not be null.");
        this.opener = Objects.requireNonNull(opener, "Connection opener must not be null.");
        this.sleeper = Objects.requireNonNull(sleeper, "Sleeper must not be null.");
    }

    Connection open() {
        for (int attempt = 0; ; attempt++) {
            try {
                return openOnce();
            } catch (SQLException failure) {
                var classification = PostgresFailureClassifier.classify(failure);
                if (!classification.retryable() || attempt >= RETRY_BACKOFFS.size()) {
                    throw failure(classification.reason(), failure);
                }
                pause(RETRY_BACKOFFS.get(attempt), failure);
            }
        }
    }

    private void pause(Duration backoff, SQLException lastFailure) {
        try {
            sleeper.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted.addSuppressed(lastFailure);
            throw new TargetConnectionFailure(
                    TargetFailureReason.UNKNOWN, settings.profile().name(), null, interrupted);
        }
    }

    private TargetConnectionFailure failure(TargetFailureReason reason, SQLException cause) {
        return new TargetConnectionFailure(reason, settings.profile().name(), cause.getSQLState(), cause);
    }

    private Connection openOnce() throws SQLException {
        var secret = password.get();
        var properties = properties(secret);
        Connection connection;
        try {
            connection = opener.open(settings.jdbcUrl(), properties);
        } finally {
            Arrays.fill(secret, '\0');
            properties.clear();
        }
        try {
            configure(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private Properties properties(char[] secret) {
        var properties = new Properties();
        properties.setProperty("user", settings.user());
        properties.setProperty("password", new String(secret));
        properties.setProperty("sslmode", settings.profile().sslMode().configValue());
        settings.optionalSslRootCert().ifPresent(path -> properties.setProperty("sslrootcert", path.toString()));
        properties.setProperty("connectTimeout", String.valueOf(settings.profile().connectTimeout().toSeconds()));
        properties.setProperty("socketTimeout", String.valueOf(settings.socketTimeout().toSeconds()));
        properties.setProperty("ApplicationName", APPLICATION_NAME);
        properties.setProperty("options", settings.sessionOptions());
        return properties;
    }

    private void configure(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        connection.setReadOnly(true);
        try (Statement statement = connection.createStatement()) {
            requireSetting(statement, "default_transaction_read_only", "on");
            requireSetting(statement, "statement_timeout", settings.expectedStatementTimeout());
            requireSetting(statement, "lock_timeout", settings.expectedLockTimeout());
            requireSetting(statement, "application_name", APPLICATION_NAME);
        }
    }

    private static void requireSetting(Statement statement, String setting, String expected) throws SQLException {
        try (var result = statement.executeQuery("SHOW " + setting)) {
            var actual = result.next() ? result.getString(1) : null;
            if (!expected.equals(actual)) {
                throw new SessionSettingMismatch(setting);
            }
        }
    }

    /** SQLSTATE-less on purpose: the classifier reports it as UNKNOWN and it is never retried. */
    private static final class SessionSettingMismatch extends SQLException {
        SessionSettingMismatch(String setting) {
            super("Session setting " + setting + " does not have the expected value.");
        }
    }

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, Properties properties) throws SQLException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
