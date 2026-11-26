package com.vigil.core.domain;

import java.util.List;
import java.util.Objects;

/** A bounded index proposal. It is neither a Recommendation nor executable SQL. */
public record OptimizationHypothesis(
        SafeRelationReference relation,
        List<SafeCatalogReference> columns,
        Finding origin
) {
    public OptimizationHypothesis {
        Objects.requireNonNull(relation, "Hypothesis relation must not be null.");
        columns = List.copyOf(Objects.requireNonNull(columns, "Hypothesis columns must not be null."));
        Objects.requireNonNull(origin, "Origin finding must not be null.");
        if (columns.isEmpty() || columns.stream().anyMatch(Objects::isNull)
                || columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException("An index hypothesis requires distinct pseudonymized columns.");
        }
    }
}
