package com.vigil.core.domain;

import com.vigil.core.domain.enums.PlanNodeType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe per-node plan facts. Filter text and values never cross the adapter boundary. */
public record PlanNodeEvidence(
        PlanNodeType type,
        Optional<SafeRelationReference> relation,
        boolean filterPresent,
        List<SafeCatalogReference> filterColumns,
        boolean filterColumnsKnown,
        long estimatedRows
) {
    public PlanNodeEvidence {
        Objects.requireNonNull(type, "Plan node type must not be null.");
        Objects.requireNonNull(relation, "Plan relation must not be null.");
        filterColumns = List.copyOf(Objects.requireNonNull(filterColumns, "Filter columns must not be null."));
        if (estimatedRows < 0) throw new IllegalArgumentException("Estimated rows must not be negative.");
        if (!filterPresent && (!filterColumns.isEmpty() || !filterColumnsKnown)) {
            throw new IllegalArgumentException("A node without filter cannot carry filter-column facts.");
        }
        if (!filterColumnsKnown && !filterColumns.isEmpty()) {
            throw new IllegalArgumentException("Unknown filter columns must be empty.");
        }
    }
}
