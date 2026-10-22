package com.vigil.core.domain;

import java.util.Objects;
import java.util.Optional;

/** One key position of an index: a plain column, or an expression whose text is never carried. */
public record IndexKey(Optional<SafeCatalogReference> column) {
    public IndexKey {
        Objects.requireNonNull(column, "Index key column must not be null.");
    }

    public static IndexKey ofColumn(SafeCatalogReference column) {
        return new IndexKey(Optional.of(Objects.requireNonNull(column, "Index key column must not be null.")));
    }

    public static IndexKey ofExpression() {
        return new IndexKey(Optional.empty());
    }

    public boolean isExpression() {
        return column.isEmpty();
    }
}
