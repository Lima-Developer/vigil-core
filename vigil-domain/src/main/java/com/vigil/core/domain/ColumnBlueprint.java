package com.vigil.core.domain;

import java.util.Objects;

/** A column by pseudonym, with its SQL type (user-defined types are reported as {@code user_defined}) and nullability. */
public record ColumnBlueprint(SafeCatalogReference column, String sqlType, boolean nullable) {
    private static final int MAX_TYPE_LENGTH = 128;

    public ColumnBlueprint {
        Objects.requireNonNull(column, "Column reference must not be null.");

        if (sqlType == null || sqlType.isBlank() || sqlType.length() > MAX_TYPE_LENGTH
                || !sqlType.matches("[a-z0-9_ (),\\[\\]\"]+")) {
            throw new IllegalArgumentException("SQL type must be a catalog type name.");
        }
    }
}
