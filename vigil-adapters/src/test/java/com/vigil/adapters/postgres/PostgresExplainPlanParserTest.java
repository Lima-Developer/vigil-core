package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.enums.PlanNodeType;
import java.util.List;
import com.vigil.adapters.postgres.schema.Pseudonymizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresExplainPlanParserTest {
    @Test void mapsNestedNodesAndRoundsRootRows() {
        String json = """
                [{"Plan":{"Node Type":"Hash Join","Plan Rows":1234.5,"Total Cost":18.75,"Filter":"secret",
                 "Plans":[{"Node Type":"Index Only Scan"},{"Node Type":"Seq Scan","Plans":[{"Node Type":"Other"}]}]}}]
                """;
        var summary = PostgresExplainPlanParser.parse(json);
        assertEquals(1235, summary.estimatedRows());
        assertEquals(0, new java.math.BigDecimal("18.75").compareTo(summary.estimatedTotalCost().orElseThrow()));
        assertEquals(List.of(PlanNodeType.HASH_JOIN, PlanNodeType.INDEX_SCAN,
                PlanNodeType.SEQUENTIAL_SCAN, PlanNodeType.UNKNOWN), summary.nodeTypes());
        assertFalse(summary.toString().contains("secret"));
    }

    @ParameterizedTest @ValueSource(strings={"not json", "[]", "[{}]", "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":-1}]"})
    void rejectsUnreadable(String json) {
        var error = assertThrows(PlanCollectionRejected.class, () -> PostgresExplainPlanParser.parse(json));
        assertEquals(PlanRejectionReason.PLAN_UNREADABLE, error.reason());
        assertFalse(error.getMessage().contains(json));
    }

    @Test void mapsBitmapIndexScanUsedByLaboratory() {
        var result = PostgresExplainPlanParser.parse("[{\"Plan\":{\"Node Type\":\"Bitmap Heap Scan\",\"Plan Rows\":15,\"Plans\":[{\"Node Type\":\"Bitmap Index Scan\"}]}}]");
        assertEquals(List.of(PlanNodeType.UNKNOWN, PlanNodeType.INDEX_SCAN), result.nodeTypes());
    }

    @Test void acceptsMaximumRepresentableRowsAndRejectsOverflow() {
        assertEquals(Long.MAX_VALUE, PostgresExplainPlanParser.parse("[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":9223372036854775807}}]").estimatedRows());
        assertEquals(PlanRejectionReason.PLAN_UNREADABLE, assertThrows(PlanCollectionRejected.class,
                () -> PostgresExplainPlanParser.parse("[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":9223372036854775808}}]")).reason());
    }

    @Test void acceptsZeroAndIndexScan() {
        var result = PostgresExplainPlanParser.parse("[{\"Plan\":{\"Node Type\":\"Index Scan\",\"Plan Rows\":0}}]");
        assertEquals(0, result.estimatedRows());
        assertEquals(List.of(PlanNodeType.INDEX_SCAN), result.nodeTypes());
    }

    @Test
    void extractsOnlyPseudonymizedFilterColumnsAndNeverCarriesFilterText() {
        var summary = PostgresExplainPlanParser.parse("""
                [{"Plan":{"Node Type":"Seq Scan","Schema":"public","Relation Name":"orders",
                "Plan Rows":100,"Filter":"((customer_id = 42) AND (status = 'OPEN'::text))"}}]
                """, new Pseudonymizer(new byte[32]));
        var node = summary.nodes().getFirst();
        assertAll(
                () -> assertTrue(node.filterPresent()),
                () -> assertTrue(node.filterColumnsKnown()),
                () -> assertEquals(2, node.filterColumns().size()),
                () -> assertFalse(summary.toString().contains("customer_id")),
                () -> assertFalse(summary.toString().contains("OPEN")),
                () -> assertFalse(summary.toString().contains("42"))
        );
    }

    @Test
    void ambiguousOrFunctionalFilterFailsClosedWithUnknownColumns() {
        var summary = PostgresExplainPlanParser.parse("""
                [{"Plan":{"Node Type":"Seq Scan","Schema":"public","Relation Name":"orders",
                "Plan Rows":100,"Filter":"(lower(status) = 'open' OR customer_id = 42)"}}]
                """, new Pseudonymizer(new byte[32]));
        assertAll(
                () -> assertTrue(summary.nodes().getFirst().filterPresent()),
                () -> assertFalse(summary.nodes().getFirst().filterColumnsKnown()),
                () -> assertTrue(summary.nodes().getFirst().filterColumns().isEmpty())
        );
    }
}
