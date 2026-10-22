package com.vigil.core.domain;

import com.vigil.core.domain.enums.ConstraintKind;

import java.util.List;
import java.util.Objects;

/** Kind and constrained columns only; constraint names and CHECK expressions are never carried. */
public record ConstraintBlueprint(ConstraintKind kind, List<SafeCatalogReference> columns) {
    public ConstraintBlueprint {
        Objects.requireNonNull(kind, "Constraint kind must not be null.");
        columns = List.copyOf(Objects.requireNonNull(columns, "Constraint columns must not be null."));
    }
}
