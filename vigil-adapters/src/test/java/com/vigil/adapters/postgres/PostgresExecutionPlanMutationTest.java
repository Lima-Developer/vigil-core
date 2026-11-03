package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.enums.PlanNodeType;
import org.junit.jupiter.api.Test;

class PostgresExecutionPlanMutationTest {
    @Test void guardBoundaryAndClause() {
        assertEquals(8192, PostgresSelectStatementGuard.validate("SELECT " + "x".repeat(8185)).length());
        assertEquals(PlanRejectionReason.TOO_LARGE, assertThrows(PlanCollectionRejected.class,
                () -> PostgresSelectStatementGuard.validate("SELECT " + "x".repeat(8186))).reason());
        assertEquals(PlanRejectionReason.FORBIDDEN_CLAUSE, assertThrows(PlanCollectionRejected.class,
                () -> PostgresSelectStatementGuard.validate("SELECT 1 FOR UPDATE")).reason());
        assertEquals(PlanRejectionReason.MULTIPLE_STATEMENTS, assertThrows(PlanCollectionRejected.class,
                () -> PostgresSelectStatementGuard.validate("SELECT 1;")).reason());
    }

    @Test void parserRecursionAndRounding() {
        var result = PostgresExplainPlanParser.parse("[{\"Plan\":{\"Node Type\":\"Hash Join\",\"Plan Rows\":1234.5,\"Plans\":[{\"Node Type\":\"Index Only Scan\"}]}}]");
        assertEquals(1235, result.estimatedRows());
        assertEquals(java.util.List.of(PlanNodeType.HASH_JOIN, PlanNodeType.INDEX_SCAN), result.nodeTypes());
    }
}
