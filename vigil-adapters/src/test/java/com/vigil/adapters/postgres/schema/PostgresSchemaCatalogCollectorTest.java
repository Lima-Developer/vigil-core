package com.vigil.adapters.postgres.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.core.domain.IndexKey;
import com.vigil.core.domain.enums.ConstraintKind;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.enums.SchemaIncompleteness;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the JDBC reading path with a scripted connection, without Docker. */
class PostgresSchemaCatalogCollectorTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final List<RelationSelector> WANTED = List.of(new RelationSelector("payroll_secret", "salaries_secret"));

    private final List<String> calls = new ArrayList<>();
    private final List<Object> arrays = new ArrayList<>();
    private final List<Integer> boundArrays = new ArrayList<>();
    private SQLException failure;

    @Test
    void readsFourCatalogQueriesInOneReadOnlyRolledBackTransaction() {
        var blueprint = collector(script()).collect(NOW, WANTED);

        assertEquals(List.of("readOnly=true", "autoCommit=false", "isolation=4",
                "prepare:" + CatalogQueries.RELATIONS, "timeout=10", "query",
                "prepare:" + CatalogQueries.COLUMNS, "timeout=10", "query",
                "prepare:" + CatalogQueries.CONSTRAINTS, "timeout=10", "query",
                "prepare:" + CatalogQueries.INDEXES, "timeout=10", "query", "rollback", "close"), calls);
        assertEquals(NOW, blueprint.collectedAt());
        assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        assertTrue(blueprint.complete());
        var relation = blueprint.relations().getFirst();
        assertEquals(RelationType.MATERIALIZED_VIEW, relation.type());
        assertEquals(2, relation.columns().size());
        assertEquals(ConstraintKind.UNIQUE, relation.constraints().getFirst().kind());
        assertEquals(2, relation.constraints().getFirst().columns().size());
        assertEquals(1, relation.indexes().size());
        assertTrue(relation.indexes().getFirst().partial());
        assertEquals(List.of(IndexKey.ofColumn(relation.columns().get(1).column())), relation.indexes().getFirst().keys());
        assertFalse(blueprint.toString().contains("secret"));
    }

    @Test
    void bindsSelectorsAsTextArraysAndOidsAsBigintArrays() {
        collector(script()).collect(NOW, WANTED);

        assertEquals(List.of("payroll_secret"), Arrays.asList((Object[]) arrays.get(0)));
        assertEquals(List.of("salaries_secret"), Arrays.asList((Object[]) arrays.get(1)));
        for (int index = 2; index < 5; index++) {
            assertEquals(List.of(10L), Arrays.asList((Object[]) arrays.get(index)));
        }
        assertEquals(5, arrays.size());
        assertEquals(List.of(1, 2, 1, 1, 1), boundArrays);
    }

    @Test
    void duplicatedSelectorsAreRequestedOnce() {
        var selector = WANTED.getFirst();
        collector(script()).collect(NOW, List.of(selector, selector));

        assertEquals(List.of("payroll_secret"), Arrays.asList((Object[]) arrays.get(0)));
        assertTrue(collector(script()).collect(NOW, List.of(selector, selector)).complete());
    }

    @Test
    void indexReturnedTwiceByTheCatalogIsReportedOnce() {
        var rows = script();
        rows.put(CatalogQueries.INDEXES, new Object[][]{
                {10L, 100L, "idx_secret", "btree", false, false, true, true, 1, "2"},
                {10L, 100L, "idx_secret", "btree", false, false, true, true, 1, "2"}});

        assertEquals(1, collector(rows).collect(NOW, WANTED).relations().getFirst().indexes().size());
    }

    @Test
    void noRelationFoundStopsAfterTheFirstQueryAndIsIncomplete() {
        var rows = script();
        rows.put(CatalogQueries.RELATIONS, new Object[0][]);

        var blueprint = collector(rows).collect(NOW, WANTED);

        assertEquals(Set.of(SchemaIncompleteness.RELATION_NOT_VISIBLE), blueprint.incompleteness());
        assertTrue(blueprint.relations().isEmpty());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("prepare:")).count());
    }

    @Test
    void missingPrivilegeIsAnIncompleteBlueprintNotAFailure() {
        failure = new SQLException("permission denied for table pg_index payroll_secret", "42501");

        var blueprint = collector(script()).collect(NOW, WANTED);

        assertEquals(Set.of(SchemaIncompleteness.CATALOG_PRIVILEGE_DENIED), blueprint.incompleteness());
        assertEquals(NOW, blueprint.collectedAt());
        assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        assertEquals("close", calls.getLast());
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08006", "57014", "XX000", "42P01"})
    void otherFailuresKeepOnlyTheSqlStateAndNeverTheDriverMessage(String sqlState) {
        failure = new SQLException("relation payroll_secret.salaries_secret broke", sqlState,
                new RuntimeException("cause payroll_secret"));

        var thrown = assertThrows(SchemaCollectionException.class, () -> collector(script()).collect(NOW, WANTED));

        assertEquals(sqlState, thrown.sqlState());
        assertEquals("PostgreSQL catalog collection failed (SQLSTATE " + sqlState + ").", thrown.getMessage());
        assertNull(thrown.getCause());
        assertEquals("close", calls.getLast());
    }

    @Test
    void failureWithoutSqlStateIsStillSafe() {
        failure = new SQLException("payroll_secret");

        var thrown = assertThrows(SchemaCollectionException.class, () -> collector(script()).collect(NOW, WANTED));

        assertNull(thrown.sqlState());
        assertEquals("PostgreSQL catalog collection failed.", thrown.getMessage());
    }

    @Test
    void rejectsMissingArguments() {
        var collector = collector(script());
        assertThrows(NullPointerException.class, () -> collector.collect(null, WANTED));
        assertThrows(NullPointerException.class, () -> collector.collect(NOW, null));
        assertThrows(IllegalArgumentException.class, () -> collector.collect(NOW, List.of()));
        assertThrows(NullPointerException.class, () -> new PostgresSchemaCatalogCollector(null));
        assertThrows(NullPointerException.class, () -> new PostgresSchemaCatalogCollector(() -> null, null));
        assertTrue(calls.isEmpty());
    }

    @Test
    void saltIsDrawnPerCollection() {
        var salts = new ArrayList<byte[]>();
        var collector = new PostgresSchemaCatalogCollector(() -> connection(script()), () -> {
            byte[] salt = new byte[32];
            salt[0] = (byte) (salts.size() + 1);
            salts.add(salt);
            return salt;
        });

        var first = collector.collect(NOW, WANTED).relations().getFirst().relation();
        var second = collector.collect(NOW, WANTED).relations().getFirst().relation();

        assertEquals(2, salts.size());
        assertFalse(first.equals(second));
    }

    @Test
    void defaultSaltYieldsDifferentPseudonymsPerCollection() {
        var collector = new PostgresSchemaCatalogCollector(() -> connection(script()));

        assertFalse(collector.collect(NOW, WANTED).relations().getFirst().relation()
                .equals(collector.collect(NOW, WANTED).relations().getFirst().relation()));
    }

    private PostgresSchemaCatalogCollector collector(Map<String, Object[][]> rows) {
        return new PostgresSchemaCatalogCollector(() -> connection(rows), () -> new byte[32]);
    }

    private static Map<String, Object[][]> script() {
        var rows = new java.util.LinkedHashMap<String, Object[][]>();
        rows.put(CatalogQueries.RELATIONS, new Object[][]{{10L, "payroll_secret", "salaries_secret", "m", 12.0, 3L}});
        rows.put(CatalogQueries.COLUMNS, new Object[][]{
                {10L, 1, "cpf_secret", "text", false}, {10L, 2, "bonus_secret", "integer", true}});
        rows.put(CatalogQueries.CONSTRAINTS, new Object[][]{{10L, "u", "1 2"}});
        rows.put(CatalogQueries.INDEXES, new Object[][]{
                {10L, 100L, "idx_secret", "btree", false, false, true, true, 1, "2"}});
        return rows;
    }

    private Connection connection(Map<String, Object[][]> rows) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setReadOnly" -> record("readOnly=" + args[0]);
                    case "setAutoCommit" -> record("autoCommit=" + args[0]);
                    case "setTransactionIsolation" -> record("isolation=" + args[0]);
                    case "rollback" -> record("rollback");
                    case "close" -> record("close");
                    case "createArrayOf" -> {
                        arrays.add(args[1]);
                        yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Array.class},
                                (a, m, x) -> null);
                    }
                    case "prepareStatement" -> {
                        calls.add("prepare:" + args[0]);
                        if (failure != null) {
                            throw failure;
                        }
                        yield statement(rows.get((String) args[0]));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement(Object[][] rows) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> record("timeout=" + args[0]);
                    case "setArray" -> {
                        boundArrays.add((int) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    case "executeQuery" -> {
                        calls.add("query");
                        yield resultSet(rows);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ResultSet resultSet(Object[][] rows) {
        int[] position = {-1};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return ++position[0] < rows.length;
                        case "close":
                            return null;
                        case "getLong":
                            return ((Number) cell(rows, position[0], (int) args[0])).longValue();
                        case "getInt":
                            return ((Number) cell(rows, position[0], (int) args[0])).intValue();
                        case "getDouble":
                            return ((Number) cell(rows, position[0], (int) args[0])).doubleValue();
                        case "getString":
                            return cell(rows, position[0], (int) args[0]);
                        case "getBoolean":
                            return cell(rows, position[0], (int) args[0]);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static Object cell(Object[][] rows, int row, int column) {
        return rows[row][column - 1];
    }

    private Object record(String call) {
        calls.add(call);
        return null;
    }
}
