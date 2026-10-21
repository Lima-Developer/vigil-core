package com.vigil.core.domain;

import com.vigil.core.domain.enums.RelationType;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A relation of the target. {@code estimatedRows} (reltuples) and {@code pages} (relpages) are planner estimates,
 * never exact counts; an unknown estimate (never analyzed) is empty rather than zero.
 */
public record RelationBlueprint(
        SafeRelationReference relation,
        SafeCatalogReference schema,
        RelationType type,
        OptionalLong estimatedRows,
        long pages,
        List<ColumnBlueprint> columns,
        List<ConstraintBlueprint> constraints,
        List<IndexBlueprint> indexes
) {
    public RelationBlueprint {
        Objects.requireNonNull(relation, "Relation reference must not be null.");
        Objects.requireNonNull(schema, "Schema reference must not be null.");
        Objects.requireNonNull(type, "Relation type must not be null.");
        Objects.requireNonNull(estimatedRows, "Estimated rows must not be null.");
        columns = List.copyOf(Objects.requireNonNull(columns, "Columns must not be null."));
        constraints = List.copyOf(Objects.requireNonNull(constraints, "Constraints must not be null."));
        indexes = List.copyOf(Objects.requireNonNull(indexes, "Indexes must not be null."));

        if (estimatedRows.isPresent() && estimatedRows.getAsLong() < 0) {
            throw new IllegalArgumentException("Estimated rows must not be negative.");
        }

        if (pages < 0) {
            throw new IllegalArgumentException("Pages must not be negative.");
        }

        if (indexes.stream().map(IndexBlueprint::index).distinct().count() != indexes.size()) {
            throw new IllegalArgumentException("An index must appear once per relation.");
        }
    }
}
