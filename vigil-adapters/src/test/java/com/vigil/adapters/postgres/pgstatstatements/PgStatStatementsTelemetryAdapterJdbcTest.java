package com.vigil.adapters.postgres.pgstatstatements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import com.vigil.application.preflight.CapabilityCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the JDBC reading path with a scripted connection, without Docker. */
class PgStatStatementsTelemetryAdapterJdbcTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final OffsetDateTime RESET = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final PostgresPreflightFacts READY =
            new PostgresPreflightFacts(160004, "db", true, true, true, true, "on", "on");

    private final List<String> calls = new ArrayList<>();
    private SQLException failure;

    @Test
    void readsRowsInReadOnlyTransactionAndClosesTheConnection() {
        TelemetryCollection collection = adapter(READY, new Object[][]{
                {5L, 10L, -7L, true, 50L, 1234.5, 750L, 1L, 2L, 3L, 4L},
                {5L, 10L, null, true, 50L, 1.0, 750L, 1L, 2L, 3L, 4L},
                {5L, 10L, 8L, false, 0L, null, 0L, 0L, 0L, 0L, 0L},
                {5L, 11L, 9L, false, 0L, 0.0, 0L, 0L, 0L, 0L, 0L}}).collect(NOW);

        assertEquals(List.of("readOnly=true", "autoCommit=false", "timeout=10",
                "query:" + PgStatStatementsTelemetryAdapter.INFO_SQL, "timeout=10",
                "query:" + PgStatStatementsTelemetryAdapter.COLLECTION_SQL, "rollback", "close"), calls);
        assertEquals(NOW, collection.collectedAt());
        assertEquals(RESET.toInstant(), collection.statsReset().orElseThrow());
        assertEquals(3, collection.dealloc());
        assertTrue(collection.fullVisibility());
        assertEquals(2, collection.skippedRows());
        assertEquals(2, collection.queries().size());
        var first = collection.queries().getFirst();
        assertEquals(-7L, first.queryId());
        assertEquals(new BigDecimal("1234.5"), first.totalExecutionMillis());
        assertEquals(750L, first.rows());
        assertTrue(first.topLevel());
        assertFalse(collection.queries().get(1).topLevel());
        assertEquals(11L, collection.queries().get(1).userid());
        assertEquals(0L, collection.queries().get(1).calls());
    }

    @Test
    void partialVisibilityIsReportedFromThePreflightFacts() {
        var facts = new PostgresPreflightFacts(160004, "db", true, true, true, false, "on", "on");
        assertFalse(adapter(facts, new Object[0][]).collect(NOW).fullVisibility());
    }

    @Test
    void sqlFailureBecomesUnavailableWithoutLeakingTheStatement() {
        failure = new SQLException("boom SELECT secret", "08006");
        var thrown = assertThrows(TelemetryUnavailableException.class, () -> adapter(READY, new Object[0][]).collect(NOW));
        assertEquals(CapabilityCode.CONNECTION, thrown.missing());
        assertFalse(thrown.getMessage().contains("secret"));
    }

    @Test
    void insufficientPrivilegeIsReportedAsStatsReadable() {
        failure = new SQLException("denied", "42501");
        var thrown = assertThrows(TelemetryUnavailableException.class, () -> adapter(READY, new Object[0][]).collect(NOW));
        assertEquals(CapabilityCode.STATS_READABLE, thrown.missing());
    }

    private PgStatStatementsTelemetryAdapter adapter(PostgresPreflightFacts facts, Object[][] rows) {
        return new PgStatStatementsTelemetryAdapter(() -> connection(rows), () -> facts);
    }

    private Connection connection(Object[][] rows) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setReadOnly" -> record("readOnly=" + args[0]);
                    case "setAutoCommit" -> record("autoCommit=" + args[0]);
                    case "rollback", "close" -> record(method.getName());
                    case "createStatement" -> statement(rows);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object record(String call) {
        calls.add(call);
        return null;
    }

    private Statement statement(Object[][] rows) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> {
                        yield record("timeout=" + args[0]);
                    }
                    case "executeQuery" -> {
                        if (failure != null) throw failure;
                        calls.add("query:" + args[0]);
                        yield args[0].equals(PgStatStatementsTelemetryAdapter.INFO_SQL)
                                ? result(new Object[][]{{RESET, 3L}}) : result(rows);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet result(Object[][] rows) {
        int[] cursor = {-1};
        boolean[] wasNull = new boolean[1];
        return (ResultSet) Proxy.newProxyInstance(PgStatStatementsTelemetryAdapterJdbcTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next" -> {
                            return ++cursor[0] < rows.length;
                        }
                        case "wasNull" -> {
                            return wasNull[0];
                        }
                        case "getLong", "getDouble", "getBoolean", "getObject" -> {
                            Object value = rows[cursor[0]][(int) args[0] - 1];
                            wasNull[0] = value == null;
                            return switch (method.getName()) {
                                case "getLong" -> value == null ? 0L : (Long) value;
                                case "getDouble" -> value == null ? 0.0 : (Double) value;
                                case "getBoolean" -> value != null && (Boolean) value;
                                default -> value;
                            };
                        }
                        case "close" -> {
                            return null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
