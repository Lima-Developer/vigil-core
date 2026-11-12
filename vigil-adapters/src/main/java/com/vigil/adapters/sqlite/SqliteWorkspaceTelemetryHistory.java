package com.vigil.adapters.sqlite;

import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, local retention for aggregate observations; it never stores SQL text or credentials. */
public final class SqliteWorkspaceTelemetryHistory implements WorkspaceTelemetryHistory {
    private final SqliteConnectionFactory connections;

    public SqliteWorkspaceTelemetryHistory(SqliteConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    @Override
    public void append(WorkspaceTelemetrySample sample, Instant deleteBefore, int maximumSamples) {
        Objects.requireNonNull(sample);
        Objects.requireNonNull(deleteBefore);
        if (maximumSamples < 1) {
            throw new IllegalArgumentException("maximumSamples");
        }
        try (var c = connections.openForWrite()) {
            c.setAutoCommit(false);
            try (var p = c.prepareStatement(
                    "INSERT OR REPLACE INTO workspace_telemetry_sample VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                set(p, 1, sample.observedAt().toString());
                set(p, 2, text(sample.windowStart()));
                set(p, 3, sample.targetId());
                set(p, 4, sample.state());
                set(p, 5, sample.quality());
                set(p, 6, sample.calls());
                set(p, 7, text(sample.executionMillis()));
                set(p, 8, text(sample.meanExecutionMillis()));
                set(p, 9, sample.rows());
                set(p, 10, sample.sharedHits());
                set(p, 11, sample.sharedReads());
                set(p, 12, sample.tempReads());
                set(p, 13, sample.tempWrites());
                set(p, 14, sample.activeSessions());
                set(p, 15, sample.waitingSessions());
                set(p, 16, sample.blockedSessions());
                set(p, 17, sample.reason());
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("DELETE FROM workspace_telemetry_sample WHERE observed_at < ?")) {
                p.setString(1, deleteBefore.toString());
                p.executeUpdate();
            }
            try (var p = c.prepareStatement(
                    "DELETE FROM workspace_telemetry_sample WHERE observed_at NOT IN "
                            + "(SELECT observed_at FROM workspace_telemetry_sample ORDER BY observed_at DESC LIMIT ?)")) {
                p.setInt(1, maximumSamples);
                p.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new SqlitePersistenceException("Workspace telemetry could not be stored.", e);
        }
    }

    @Override
    public List<WorkspaceTelemetrySample> recent(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit");
        }
        var out = new ArrayList<WorkspaceTelemetrySample>();
        try (var c = connections.open();
                var p = c.prepareStatement(
                        "SELECT * FROM workspace_telemetry_sample ORDER BY observed_at DESC LIMIT ?")) {
            p.setInt(1, limit);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    out.add(new WorkspaceTelemetrySample(
                            Instant.parse(r.getString("observed_at")),
                            instant(r.getString("window_start")),
                            r.getString("target_id"),
                            r.getString("state"),
                            r.getString("quality"),
                            number(r, "calls"),
                            decimal(r.getString("execution_millis")),
                            decimal(r.getString("mean_execution_millis")),
                            number(r, "rows_count"),
                            number(r, "shared_hits"),
                            number(r, "shared_reads"),
                            number(r, "temp_reads"),
                            number(r, "temp_writes"),
                            number(r, "active_sessions"),
                            number(r, "waiting_sessions"),
                            number(r, "blocked_sessions"),
                            r.getString("reason")));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new SqlitePersistenceException("Workspace telemetry could not be read.", e);
        }
    }

    private static void set(java.sql.PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setObject(index, value);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Long number(java.sql.ResultSet resultSet, String name) throws SQLException {
        long value = resultSet.getLong(name);
        return resultSet.wasNull() ? null : value;
    }
}
