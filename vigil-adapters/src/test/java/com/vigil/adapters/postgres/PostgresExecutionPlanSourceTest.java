package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.exception.TargetFailureReason;
import com.vigil.core.domain.enums.PlanNodeType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostgresExecutionPlanSourceTest {
    private static final String JSON = "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":1}}]";
    private final List<String> calls = new ArrayList<>();

    private Connection connection(String state) {
        return connection(state, false);
    }

    private Connection connection(String state, boolean rollbackFails) {
        return connection(state, rollbackFails, false);
    }

    private Connection connection(String state, boolean rollbackFails, boolean closeFails) {
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> { calls.add("next"); yield true; }
                    case "getString" -> "UNREADABLE".equals(state) ? "{sensitive JSON" : JSON;
                    case "close" -> {
                        calls.add("result.close");
                        if (closeFails) throw new SQLException("SELECT secret " + JSON);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setEscapeProcessing", "setQueryTimeout" -> { calls.add(method.getName() + "=" + args[0]); yield null; }
                    case "execute" -> { calls.add((String) args[0]); yield true; }
                    case "executeQuery" -> {
                        calls.add((String) args[0]);
                        if (state != null && !"UNREADABLE".equals(state))
                            throw new SQLException("SELECT secret " + JSON, state);
                        yield rows;
                    }
                    case "close" -> {
                        calls.add("statement.close");
                        if (closeFails) throw new SQLException("SELECT secret " + JSON);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> { calls.add("setAutoCommit=" + args[0]); yield null; }
                    case "createStatement" -> statement;
                    case "rollback" -> {
                        calls.add("rollback");
                        if (rollbackFails) throw new SQLException("SELECT secret " + JSON, "08006");
                        yield null;
                    }
                    case "close" -> {
                        calls.add("close");
                        if (closeFails) throw new SQLException("SELECT secret " + JSON);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void executesExactSqlAndClosesTransaction() {
        var source = new PostgresExecutionPlanSource(() -> connection(null));
        assertEquals(List.of(PlanNodeType.SEQUENTIAL_SCAN), source.collect(" SELECT 1 ").nodeTypes());
        assertEquals(List.of("setAutoCommit=false", "setEscapeProcessing=false", "setQueryTimeout=2",
                "SET LOCAL statement_timeout = '2s'", "EXPLAIN (FORMAT JSON) SELECT 1", "next",
                "result.close", "statement.close", "rollback", "close"), calls);
    }

    @ParameterizedTest @CsvSource({"57014,TIMEOUT", "55P03,TIMEOUT", "42501,TARGET_UNAVAILABLE"})
    void mapsSqlFailureAndRollsBack(String state, PlanRejectionReason reason) {
        var source = new PostgresExecutionPlanSource(() -> connection(state));
        var error = assertThrows(PlanCollectionRejected.class, () -> source.collect("SELECT 1"));
        assertEquals(reason, error.reason());
        assertFalse(error.getMessage().contains("SELECT"));
        assertFalse(error.getMessage().contains(JSON));
        assertEquals(List.of("rollback", "close"), calls.subList(calls.size()-2, calls.size()));
    }

    @ParameterizedTest @CsvSource({"57014,TIMEOUT", "55P03,TIMEOUT", "UNREADABLE,PLAN_UNREADABLE"})
    void preservesPrimaryFailureWhenRollbackFails(String state, PlanRejectionReason reason) {
        var source = new PostgresExecutionPlanSource(() -> connection(state, true));
        var error = assertThrows(PlanCollectionRejected.class, () -> source.collect("SELECT secret"));
        assertEquals(reason, error.reason());
        assertFalse(error.getMessage().contains("SELECT"));
        assertFalse(error.getMessage().contains(JSON));
        assertFalse(error.getMessage().contains("sensitive"));
        assertEquals(List.of("rollback", "close"), calls.subList(calls.size() - 2, calls.size()));
    }

    @Test void mapsRollbackFailureAfterSuccessfulRead() {
        var source = new PostgresExecutionPlanSource(() -> connection(null, true));
        assertEquals(PlanRejectionReason.TARGET_UNAVAILABLE,
                assertThrows(PlanCollectionRejected.class, () -> source.collect("SELECT 1")).reason());
        assertEquals(List.of("rollback", "close"), calls.subList(calls.size() - 2, calls.size()));
    }

    @Test void parsingAndAllClosesFailWithoutExposingJdbcErrors() {
        var source = new PostgresExecutionPlanSource(() -> connection("UNREADABLE", false, true));
        var error = assertThrows(PlanCollectionRejected.class, () -> source.collect("SELECT 1"));
        assertEquals(PlanRejectionReason.PLAN_UNREADABLE, error.reason());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains("SELECT secret"));
        assertFalse(error.toString().contains(JSON));
        assertEquals(List.of("result.close", "statement.close", "rollback", "close"),
                calls.subList(calls.size() - 4, calls.size()));
    }

    @Test void sanitizesOpeningFailureWhilePreservingClassificationAndSqlState() {
        var source = new PostgresExecutionPlanSource(() -> {
            throw new TargetConnectionFailure(TargetFailureReason.TIMEOUT, "target", "08006",
                    new SQLException("SELECT secret " + JSON, "08006"));
        });
        var error = assertThrows(TargetConnectionFailure.class, () -> source.collect("SELECT 1"));
        assertEquals(TargetFailureReason.TIMEOUT, error.reason());
        assertEquals("08006", error.sqlState().orElseThrow());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains("SELECT secret"));
        assertFalse(error.toString().contains(JSON));
    }

    @Test void createsTargetPortWithoutOpeningConnection() {
        assertNotNull(PostgresExecutionPlanSource.forTarget(PostgresTestSupport.profile(),
                "vigil_collector", null, java.util.Map.of("VIGIL_PG_PASSWORD", PostgresTestSupport.canary())));
    }

    @ParameterizedTest @CsvSource({
            "'SELECT 1;',MULTIPLE_STATEMENTS",
            "'SELECT id AS x$tag$ FROM orders FOR UPDATE -- $tag$',FORBIDDEN_CLAUSE",
            "'SELECT id AS x$tag$ INTO copied_orders FROM orders -- $tag$',FORBIDDEN_CLAUSE"})
    void rejectsBeforeOpeningConnection(String sql, PlanRejectionReason reason) {
        var source = new PostgresExecutionPlanSource(() -> { fail("connection opened"); return null; });
        assertEquals(reason, assertThrows(PlanCollectionRejected.class, () -> source.collect(sql)).reason());
    }
}
