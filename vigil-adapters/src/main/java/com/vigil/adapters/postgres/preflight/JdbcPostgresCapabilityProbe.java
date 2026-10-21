package com.vigil.adapters.postgres.preflight;

import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import com.vigil.application.preflight.CapabilityCode;
import com.vigil.application.preflight.PreflightProbeException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/** Reads only server settings and catalog metadata; the supplied connection is closed after each inspection. */
public final class JdbcPostgresCapabilityProbe implements PostgresCapabilityProbe {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final String VERSION = "SHOW server_version_num";
    private static final String DATABASE = "SELECT current_database()";
    private static final String AVAILABLE = "SELECT EXISTS (SELECT 1 FROM pg_available_extensions"
            + " WHERE name = 'pg_stat_statements')";
    private static final String INSTALLED = "SELECT EXISTS (SELECT 1 FROM pg_extension"
            + " WHERE extname = 'pg_stat_statements')";
    private static final String PRELOAD = "SELECT current_setting('shared_preload_libraries', true)";
    private static final String READABLE = "SELECT rolsuper OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER')"
            + " FROM pg_roles WHERE rolname = current_user";
    private static final String QUERY_ID = "SELECT current_setting('compute_query_id', true)";
    private static final String IO_TIMING = "SELECT current_setting('track_io_timing', true)";

    private final Supplier<Connection> connection;

    public JdbcPostgresCapabilityProbe(Supplier<Connection> connection) {
        this.connection = Objects.requireNonNull(connection, "Connection supplier não pode ser nulo");
    }

    @Override
    public PostgresPreflightFacts inspect() {
        try (Connection opened = connection.get()) {
            try {
                int version = Integer.parseInt(value(opened, VERSION));
                String database = value(opened, DATABASE);
                boolean available = booleanValue(opened, AVAILABLE);
                boolean installed = booleanValue(opened, INSTALLED);
                String preload = value(opened, PRELOAD);
                boolean preloaded = preload != null && Arrays.stream(preload.split(","))
                        .map(String::trim).anyMatch("pg_stat_statements"::equals);
                boolean readable = booleanValue(opened, READABLE);
                return new PostgresPreflightFacts(version, database, available, installed, preloaded, readable,
                        value(opened, QUERY_ID), value(opened, IO_TIMING));
            } catch (SQLException failure) {
                throw new PreflightProbeException("42501".equals(failure.getSQLState())
                        ? CapabilityCode.STATS_READABLE : CapabilityCode.CONNECTION);
            }
        } catch (SQLException failure) {
            throw new PreflightProbeException(CapabilityCode.CONNECTION);
        }
    }

    private static String value(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var result = statement.executeQuery(sql)) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static boolean booleanValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var result = statement.executeQuery(sql)) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
