package com.vigil.adapters.postgres.pgstatstatements;

import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.FingerprintedQueryCounters;
import com.vigil.application.port.out.interfaces.ImpactTelemetryPort;
import com.vigil.application.port.out.interfaces.ImpactTelemetrySnapshot;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import com.vigil.application.preflight.CapabilityCode;
import com.vigil.core.domain.QueryFingerprint;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Impact-selection collector: like {@link PgStatStatementsTelemetryAdapter} but it also reads the query text to compute
 * the family fingerprint <strong>inside the adapter</strong>. Only the fingerprint (or UNCLASSIFIED, an empty optional)
 * leaves through the port; the raw SQL never does. It is deliberately separate from the continuous collector, whose
 * measured overhead must not change.
 *
 * <p>Fail-closed: {@code "<insufficient privilege>"} (no {@code pg_read_all_stats}), empty text, or text the normalizer
 * rejects all yield UNCLASSIFIED. A fingerprint is cached per {@code (dbid, queryid)} so the two snapshots of one
 * invocation do not normalize the same text twice.
 */
public final class PgStatStatementsImpactAdapter implements ImpactTelemetryPort {
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    static final String INFO_SQL = "SELECT stats_reset, dealloc FROM pg_stat_statements_info";
    static final String COLLECTION_SQL = "SELECT dbid, userid, queryid, toplevel, calls, total_exec_time, rows,"
            + " shared_blks_hit, shared_blks_read, temp_blks_read, temp_blks_written, query"
            + " FROM pg_stat_statements"
            + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"
            + " AND query IS NOT NULL"
            + " AND ltrim(query) ~* '^(select|with)\\y'"
            + " AND query NOT ILIKE '%pg_stat_statements%'"
            + " ORDER BY dbid, userid, queryid, toplevel";

    private final Supplier<Connection> connection;
    private final PostgresCapabilityProbe probe;
    private final QueryFingerprinter fingerprinter;
    private final Map<String, Optional<QueryFingerprint>> fingerprintCache = new HashMap<>();

    public PgStatStatementsImpactAdapter(Supplier<Connection> connection, PostgresCapabilityProbe probe,
                                         QueryFingerprinter fingerprinter) {
        this.connection = Objects.requireNonNull(connection, "Connection supplier must not be null.");
        this.probe = Objects.requireNonNull(probe, "Capability probe must not be null.");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "Fingerprinter must not be null.");
    }

    @Override
    public ImpactTelemetrySnapshot collectForImpactSelection(Instant at) {
        Objects.requireNonNull(at, "Collected at must not be null.");
        var facts = Objects.requireNonNull(probe.inspect(), "Probe returned null.");
        PgStatStatementsCapabilities.firstMissing(facts).ifPresent(missing -> {
            throw new TelemetryUnavailableException(missing);
        });
        try (Connection opened = connection.get()) {
            opened.setReadOnly(true);
            opened.setAutoCommit(false);
            ImpactTelemetrySnapshot snapshot = read(opened, at, facts.statsReadable());
            opened.rollback();
            return snapshot;
        } catch (SQLException failure) {
            throw new TelemetryUnavailableException("42501".equals(failure.getSQLState())
                    ? CapabilityCode.STATS_READABLE : CapabilityCode.CONNECTION, failure);
        }
    }

    private ImpactTelemetrySnapshot read(Connection opened, Instant at, boolean fullVisibility) throws SQLException {
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
        var entries = new ArrayList<FingerprintedQueryCounters>();
        int skipped = 0;
        try (Statement statement = opened.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery(COLLECTION_SQL)) {
                while (result.next()) {
                    Optional<CumulativeQueryCounters> counters = PgStatStatementsRowMapper.map(row(result));
                    if (counters.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    // Read the text only to fingerprint it; it is never stored or returned.
                    Optional<QueryFingerprint> fingerprint = fingerprintOf(counters.get().dbid(),
                            counters.get().queryId(), result.getString(12));
                    entries.add(new FingerprintedQueryCounters(counters.get(), fingerprint));
                }
            }
        }
        return new ImpactTelemetrySnapshot(at, statsReset, dealloc, fullVisibility, entries, skipped);
    }

    private Optional<QueryFingerprint> fingerprintOf(long dbid, long queryId, String text) {
        return fingerprintCache.computeIfAbsent(dbid + ":" + queryId,
                key -> ImpactFingerprintClassifier.classify(text, fingerprinter));
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
