package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.enums.PlanNodeType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresExplainPlanParserTest {
    @Test void mapsNestedNodesAndRoundsRootRows() {
        String json = """
                [{"Plan":{"Node Type":"Hash Join","Plan Rows":1234.5,"Filter":"secret",
                 "Plans":[{"Node Type":"Index Only Scan"},{"Node Type":"Seq Scan","Plans":[{"Node Type":"Other"}]}]}}]
                """;
        var summary = PostgresExplainPlanParser.parse(json);
        assertEquals(1235, summary.estimatedRows());
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
}
