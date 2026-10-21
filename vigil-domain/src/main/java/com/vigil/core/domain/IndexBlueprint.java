package com.vigil.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * An existing index. {@code partial} says only that a predicate exists: its text can hold business literals and is
 * never carried.
 */
public record IndexBlueprint(
        SafeCatalogReference index,
        String accessMethod,
        List<IndexKey> keys,
        boolean unique,
        boolean primary,
        boolean partial,
        boolean valid
) {
    public IndexBlueprint {
        Objects.requireNonNull(index, "Index reference must not be null.");
        keys = List.copyOf(Objects.requireNonNull(keys, "Index keys must not be null."));

        if (accessMethod == null || !accessMethod.matches("[a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Access method must be a catalog access method name.");
        }

        if (keys.isEmpty()) {
            throw new IllegalArgumentException("An index must have at least one key.");
        }
    }
}
