package com.vigil.core.domain;

/**
 * Pseudonym of a schema, column or index name. It is only comparable inside the blueprint it came from: the salt that
 * produced it lives for a single collection.
 */
public record SafeCatalogReference(String value) {
    public SafeCatalogReference {
        if (value == null || !value.matches("(schema|column|index)_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Catalog reference must be a pseudonymized identifier.");
        }
    }
}
