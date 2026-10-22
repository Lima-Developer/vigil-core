package com.vigil.adapters.postgres.pgstatstatements;

import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.port.out.interfaces.QueryTelemetryPort;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import com.vigil.application.preflight.CapabilityCode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads the cumulative counters of {@code pg_stat_statements} with a fixed column list. The statement text is used
 * only inside the {@code WHERE} clause to keep SELECT/WITH entries of the current database and to leave out the
 * collector's own queries; it is never selected. The supplied connection is made read-only and closed after each
 * collection.
 */
public final class PgStatStatementsTelemetryAdapter implements QueryTelemetryPort {
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    static final String INFO_SQL = "SELECT stats_reset, dealloc FROM pg_stat_statements_info";
    static final String COLLECTION_SQL = "SELECT dbid, userid, queryid, toplevel, calls, total_exec_time, rows,"
            + " shared_blks_hit, shared_blks_read, temp_blks_read, temp_blks_written"
            + " FROM pg_stat_statements"
            + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"
            + " AND query IS NOT NULL"
            + " AND ltrim(query) ~* '^(select|with)\\y'"
            + " AND query NOT ILIKE '%pg_stat_statements%'"
            + " ORDER BY dbid, userid, queryid, toplevel";

    private final Supplier<Connection> connection;
    private final PostgresCapabilityProbe probe;

    public PgStatStatementsTelemetryAdapter(Supplier<Connection> connection, PostgresCapabilityProbe probe) {
        this.connection = Objects.requireNonNull(connection, "Connection supplier must not be null.");
        this.probe = Objects.requireNonNull(probe, "Capability probe must not be null.");
    }

    @Override
    public TelemetryCollection collect(Instant collectedAt) {
        Objects.requireNonNull(collectedAt, "Collected at must not be null.");
        var facts = Objects.requireNonNull(probe.inspect(), "Probe returned null.");
        PgStatStatementsCapabilities.firstMissing(facts).ifPresent(missing -> {
            throw new TelemetryUnavailableException(missing);
        });
        try (Connection opened = connection.get()) {
            // Read-only is enforced by the server only inside a transaction, so the session is not left in autocommit.
            opened.setReadOnly(true);
            opened.setAutoCommit(false);
            TelemetryCollection collection = read(opened, collectedAt, facts.statsReadable());
            opened.rollback();
            return collection;
        } catch (SQLException failure) {
            throw new TelemetryUnavailableException("42501".equals(failure.getSQLState())
                    ? CapabilityCode.STATS_READABLE : CapabilityCode.CONNECTION, failure);
        }
    }

    private static TelemetryCollection read(Connection opened, Instant collectedAt, boolean fullVisibility)
            throws SQLException {
        Optional<Instant> statsReset = Optional.empty();
        long dealloc = 0;
        try (Statement statement = opened.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery(INFO_SQL)) {
                if (result.next()) {
                    statsReset = Optional.ofNullable(result.getObject(1, OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant);
                    dealloc = result.getLong(2);
                }
            }
        }
        var queries = new ArrayList<CumulativeQueryCounters>();
        int skipped = 0;
        try (Statement statement = opened.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery(COLLECTION_SQL)) {
                while (result.next()) {
                    Optional<CumulativeQueryCounters> counters = PgStatStatementsRowMapper.map(row(result));
                    if (counters.isPresent()) {
                        queries.add(counters.get());
                    } else {
                        skipped++;
                    }
                }
            }
        }
        return new TelemetryCollection(collectedAt, statsReset, dealloc, fullVisibility, queries, skipped);
    }

    private static PgStatStatementsRow row(ResultSet result) throws SQLException {
        return new PgStatStatementsRow(longValue(result, 1), longValue(result, 2), longValue(result, 3),
                booleanValue(result, 4), longValue(result, 5), doubleValue(result, 6), longValue(result, 7),
                longValue(result, 8), longValue(result, 9), longValue(result, 10), longValue(result, 11));
    }

    private static Long longValue(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Double doubleValue(ResultSet result, int column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static Boolean booleanValue(ResultSet result, int column) throws SQLException {
        boolean value = result.getBoolean(column);
        return result.wasNull() ? null : value;
    }
}
