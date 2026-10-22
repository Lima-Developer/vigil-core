package com.vigil.adapters.postgres.schema;

import java.util.Objects;

/**
 * A relation the case cares about, by its real catalog name. It is input only: it stays inside the trust boundary and
 * never reaches the blueprint, so {@code toString} does not print it.
 */
public record RelationSelector(String schema, String name) {
    public RelationSelector {
        if (schema == null || schema.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Relation selector needs a schema and a relation name.");
        }
    }

    @Override
    public String toString() {
        return "RelationSelector[redacted]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RelationSelector selector
                && schema.equals(selector.schema) && name.equals(selector.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, name);
    }
}
