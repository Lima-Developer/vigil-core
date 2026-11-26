package com.vigil.core.domain;

import com.vigil.core.domain.enums.DiagnosticInconclusiveReason;
import com.vigil.core.domain.enums.DiagnosticRuleCode;
import com.vigil.core.domain.enums.FindingSeverity;
import com.vigil.core.domain.enums.PlanNodeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure deterministic rules over safe plan/catalog evidence and the selected impact. */
public final class DiagnosticRuleEngine {
    private static final String R1_EXPLANATION =
            "Varredura sequencial seletiva em relação de grande porte sem índice compatível para as colunas pseudonimizadas do filtro.";

    private final DiagnosticRuleThresholds thresholds;

    public DiagnosticRuleEngine() {
        this(DiagnosticRuleThresholds.DEFAULTS);
    }

    public DiagnosticRuleEngine(DiagnosticRuleThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "Thresholds must not be null.");
    }

    public DiagnosticEvaluation evaluate(PlanEvidence plan, List<CatalogEvidence> catalog,
                                         Optional<ImpactAssessment> selectedImpact) {
        Objects.requireNonNull(plan, "Plan evidence must not be null.");
        catalog = List.copyOf(Objects.requireNonNull(catalog, "Catalog evidence must not be null."));
        Objects.requireNonNull(selectedImpact, "Selected impact must not be null.");
        if (selectedImpact.isEmpty()) {
            return new DiagnosticEvaluation(List.of(), List.of(DiagnosticInconclusiveReason.IMPACT_NOT_SELECTED));
        }
        if (plan.payload().nodes().isEmpty()) {
            return new DiagnosticEvaluation(List.of(), List.of(DiagnosticInconclusiveReason.LEGACY_PLAN_WITHOUT_NODE_FACTS));
        }

        var findings = new ArrayList<Finding>();
        var reasons = new LinkedHashSet<DiagnosticInconclusiveReason>();
        for (var node : plan.payload().nodes()) {
            if (node.type() != PlanNodeType.SEQUENTIAL_SCAN || !node.filterPresent()) continue;
            if (node.relation().isEmpty()) {
                reasons.add(DiagnosticInconclusiveReason.RELATION_NOT_CORRELATED);
                continue;
            }
            var relationEvidence = catalog.stream()
                    .filter(item -> item.relation().relation().equals(node.relation().orElseThrow()))
                    .findFirst();
            if (relationEvidence.isEmpty()) {
                reasons.add(DiagnosticInconclusiveReason.RELATION_NOT_CORRELATED);
                continue;
            }
            if (!node.filterColumnsKnown() || node.filterColumns().isEmpty()) {
                reasons.add(DiagnosticInconclusiveReason.FILTER_COLUMNS_UNKNOWN);
                continue;
            }
            var relation = relationEvidence.orElseThrow().relation();
            if (relation.estimatedRows().isEmpty() || relation.estimatedRows().getAsLong() == 0) {
                reasons.add(DiagnosticInconclusiveReason.RELATION_SIZE_UNKNOWN);
                continue;
            }
            long relationRows = relation.estimatedRows().getAsLong();
            if (relationRows < thresholds.minimumRelationRows()) continue;
            var ratio = BigDecimal.valueOf(node.estimatedRows())
                    .divide(BigDecimal.valueOf(relationRows), 8, RoundingMode.HALF_UP);
            if (ratio.compareTo(thresholds.maximumSelectiveRatio()) > 0) continue;
            if (hasCoveringIndex(relation, node.filterColumns())) continue;
            findings.add(new Finding(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, FindingSeverity.HIGH,
                    List.of(plan.evidenceId(), relationEvidence.orElseThrow().evidenceId()), R1_EXPLANATION));
        }
        return new DiagnosticEvaluation(findings, List.copyOf(reasons));
    }

    private static boolean hasCoveringIndex(RelationBlueprint relation, List<SafeCatalogReference> filterColumns) {
        return relation.indexes().stream().filter(IndexBlueprint::valid).anyMatch(index -> {
            var indexed = index.keys().stream().map(IndexKey::column).flatMap(Optional::stream).toList();
            return indexed.containsAll(filterColumns);
        });
    }

    public record PlanEvidence(UUID evidenceId, PlanSummaryEvidencePayload payload) {
        public PlanEvidence {
            Objects.requireNonNull(evidenceId, "Plan evidence id must not be null.");
            Objects.requireNonNull(payload, "Plan payload must not be null.");
        }
    }

    public record CatalogEvidence(UUID evidenceId, RelationBlueprint relation) {
        public CatalogEvidence {
            Objects.requireNonNull(evidenceId, "Catalog evidence id must not be null.");
            Objects.requireNonNull(relation, "Relation blueprint must not be null.");
        }
    }
}
