package com.vigil.core;

import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRuleEngineTest {
    private static final UUID PLAN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CATALOG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final SafeRelationReference RELATION = new SafeRelationReference("relation_orders");
    private static final SafeCatalogReference COLUMN =
            new SafeCatalogReference("column_11111111111111111111111111111111");

    @Test
    void selectiveFilterOnLargeRelationWithoutIndexProducesR1() {
        var result = evaluate(100_000, 100, List.of(), Optional.of(impact()), true);

        assertAll(
                () -> assertEquals(1, result.findings().size()),
                () -> assertEquals(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER,
                        result.findings().getFirst().ruleCode()),
                () -> assertEquals(List.of(PLAN_ID, CATALOG_ID), result.findings().getFirst().evidenceIds()),
                () -> assertTrue(result.inconclusiveReasons().isEmpty())
        );
    }

    @Test
    void sequentialScanOnSmallRelationDoesNotProduceMissingIndexFinding() {
        assertTrue(evaluate(999, 10, List.of(), Optional.of(impact()), true).findings().isEmpty());
    }

    @Test
    void compatibleExistingIndexSuppressesMissingIndexFinding() {
        var index = new IndexBlueprint(new SafeCatalogReference("index_22222222222222222222222222222222"),
                "btree", List.of(IndexKey.ofColumn(COLUMN)), false, false, false, true);
        assertTrue(evaluate(100_000, 100, List.of(index), Optional.of(impact()), true).findings().isEmpty());
    }

    @Test
    void absentSelectedImpactProducesNoFinding() {
        var result = evaluate(100_000, 100, List.of(), Optional.empty(), true);
        assertEquals(List.of(DiagnosticInconclusiveReason.IMPACT_NOT_SELECTED), result.inconclusiveReasons());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void unknownFilterColumnsFailClosed() {
        var result = evaluate(100_000, 100, List.of(), Optional.of(impact()), false);
        assertTrue(result.findings().isEmpty());
        assertEquals(List.of(DiagnosticInconclusiveReason.FILTER_COLUMNS_UNKNOWN), result.inconclusiveReasons());
    }

    @Test
    void sameInputAlwaysProducesExactlyTheSameFinding() {
        var first = evaluate(100_000, 100, List.of(), Optional.of(impact()), true);
        var second = evaluate(100_000, 100, List.of(), Optional.of(impact()), true);
        assertEquals(first, second);
    }

    @Test
    void explanationContainsNeitherSqlNorBusinessLiteral() {
        String explanation = evaluate(100_000, 100, List.of(), Optional.of(impact()), true)
                .findings().getFirst().explanation();
        assertAll(
                () -> assertFalse(explanation.toUpperCase().contains("SELECT")),
                () -> assertFalse(explanation.contains("customer_id")),
                () -> assertFalse(explanation.contains("OPEN")),
                () -> assertFalse(explanation.contains("42"))
        );
    }

    private static DiagnosticEvaluation evaluate(long relationRows, long nodeRows, List<IndexBlueprint> indexes,
                                                 Optional<ImpactAssessment> impact, boolean columnsKnown) {
        var columns = columnsKnown ? List.of(COLUMN) : List.<SafeCatalogReference>of();
        var node = new PlanNodeEvidence(PlanNodeType.SEQUENTIAL_SCAN, Optional.of(RELATION), true,
                columns, columnsKnown, nodeRows);
        var plan = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), nodeRows,
                Optional.of(BigDecimal.ONE), List.of(node));
        var relation = new RelationBlueprint(RELATION,
                new SafeCatalogReference("schema_33333333333333333333333333333333"), RelationType.TABLE,
                OptionalLong.of(relationRows), 100, List.of(), List.of(), indexes);
        return new DiagnosticRuleEngine().evaluate(new DiagnosticRuleEngine.PlanEvidence(PLAN_ID, plan),
                List.of(new DiagnosticRuleEngine.CatalogEvidence(CATALOG_ID, relation)), impact);
    }

    private static ImpactAssessment impact() {
        return new ImpactAssessment(new QueryImpactDelta(10, new BigDecimal("1000"), 100, 10, 2,
                new BigDecimal("0.80")), 1, BaselineConfidence.ABSENT);
    }
}
