package com.vigil.adapters.postgres.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogQueriesTest {
    private static final List<String> ALL = List.of(CatalogQueries.RELATIONS, CatalogQueries.COLUMNS,
            CatalogQueries.CONSTRAINTS, CatalogQueries.INDEXES);

    static List<String> all() {
        return ALL;
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyStatementIsAReadOnlyCatalogSelect(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        assertTrue(lower.startsWith("select "));
        for (String forbidden : new String[]{"count(", "insert ", "update ", "delete ", "create ", "alter ", "drop ",
                "truncate", "pg_get_indexdef", "pg_get_expr", "pg_get_constraintdef", "pg_stats", "pg_stat_",
                "pg_relation_size", "tablesample"}) {
            assertFalse(lower.contains(forbidden), forbidden + " in " + sql);
        }
        assertTrue(lower.contains("pg_class") || lower.contains("pg_attribute") || lower.contains("pg_constraint")
                || lower.contains("pg_index"));
    }

    @ParameterizedTest
    @MethodSource("all")
    void valuesAreBoundNeverConcatenated(String sql) {
        assertTrue(sql.contains("?"));
        assertFalse(sql.contains("\""));
    }

    @Test
    void relationsAreLimitedToTheSupportedKinds() {
        assertTrue(CatalogQueries.RELATIONS.contains("c.relkind IN ('r', 'm', 'p', 'f')"));
    }

    @Test
    void indexPredicateIsOnlyTestedForPresence() {
        assertTrue(CatalogQueries.INDEXES.contains("i.indpred IS NOT NULL"));
        assertFalse(CatalogQueries.INDEXES.replace("i.indpred IS NOT NULL", "").contains("indpred"));
    }

    @Test
    void userDefinedTypeNamesAreNotSelected() {
        assertTrue(CatalogQueries.COLUMNS.contains("'pg_catalog'::regnamespace"));
        assertTrue(CatalogQueries.COLUMNS.contains("'user_defined'"));
    }
}
