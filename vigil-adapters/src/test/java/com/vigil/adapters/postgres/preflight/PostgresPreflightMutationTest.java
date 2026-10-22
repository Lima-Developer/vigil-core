package com.vigil.adapters.postgres.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PostgresPreflightMutationTest {
    @Test
    void permissionSqlStateIsClassifiedAndConnectionClosed() {
        assertFailureCategory("42501", com.vigil.application.preflight.CapabilityCode.STATS_READABLE);
    }

    @Test
    void otherSqlFailureIsClassifiedAsConnectionAndConnectionClosed() {
        assertFailureCategory("08006", com.vigil.application.preflight.CapabilityCode.CONNECTION);
    }

    private void assertFailureCategory(String sqlState, com.vigil.application.preflight.CapabilityCode expected) {
        var closed = new AtomicBoolean();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) { closed.set(true); return null; }
                    if (method.getName().equals("createStatement")) {
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                                (statement, statementMethod, statementArgs) -> {
                                    if (statementMethod.getName().equals("executeQuery"))
                                        throw new SQLException("private detail", sqlState);
                                    if (statementMethod.getName().equals("close") || statementMethod.getName().equals("setQueryTimeout")) return null;
                                    throw new UnsupportedOperationException(statementMethod.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var failure = assertThrows(com.vigil.application.preflight.PreflightProbeException.class,
                () -> new JdbcPostgresCapabilityProbe(() -> connection).inspect());
        assertEquals(expected, failure.code());
        assertTrue(closed.get());
        assertTrue(failure.getMessage().contains("Falha na inspeção"));
    }

    @Test
    void readsOnlyAllowedMetadataWithTimeoutAndClosesConnection() {
        var sql = new ArrayList<String>();
        var closed = new AtomicBoolean();
        var timeouts = new ArrayList<Integer>();
        var values = Map.of(
                "SHOW server_version_num", "160004",
                "SELECT current_database()", "vigil",
                "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_stat_statements')", "true",
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')", "true",
                "SELECT current_setting('shared_preload_libraries', true)", "other, pg_stat_statements , another",
                "SELECT rolsuper OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER') FROM pg_roles WHERE rolname = current_user", "true",
                "SELECT current_setting('compute_query_id', true)", "auto",
                "SELECT current_setting('track_io_timing', true)", "off");
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closed.set(true);
                        return null;
                    }
                    if (method.getName().equals("createStatement")) {
                        return statement(sql, timeouts, values);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        var facts = new JdbcPostgresCapabilityProbe(() -> connection).inspect();

        assertEquals(160004, facts.serverVersionNum());
        assertEquals("vigil", facts.databaseName());
        assertTrue(facts.extensionAvailable());
        assertTrue(facts.extensionInstalled());
        assertTrue(facts.preloaded());
        assertTrue(facts.statsReadable());
        assertEquals("auto", facts.computeQueryId());
        assertEquals("off", facts.trackIoTiming());
        assertEquals(8, sql.size());
        assertTrue(sql.stream().allMatch(query -> query.startsWith("SHOW ") || query.startsWith("SELECT ")));
        assertTrue(sql.stream().noneMatch(query -> query.contains("pg_stat_statements.query")));
        assertEquals(List.of(5, 5, 5, 5, 5, 5, 5, 5), timeouts);
        assertTrue(closed.get());
    }

    private Statement statement(List<String> sql, List<Integer> timeouts, Map<String, String> values) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) {
                        timeouts.add((Integer) args[0]);
                        return null;
                    }
                    if (method.getName().equals("executeQuery")) {
                        String query = (String) args[0];
                        sql.add(query);
                        if (!values.containsKey(query)) {
                            throw new AssertionError(query);
                        }
                        return result(values.get(query));
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private ResultSet result(String value) {
        var first = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "next" -> first.getAndSet(false);
                        case "getString" -> value;
                        case "getBoolean" -> Boolean.parseBoolean(value);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
