package com.vigil.core.domain;

public record SafeRelationReference(String value) {
    public SafeRelationReference {
        if (value == null || !value.matches("relation_[a-z0-9_]+")) {
            throw new IllegalArgumentException("Relation reference must be a sanitized relation identifier.");
        }
    }
}
