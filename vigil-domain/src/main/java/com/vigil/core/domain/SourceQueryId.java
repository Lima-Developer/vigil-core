package com.vigil.core.domain;

public record SourceQueryId(String value) {
    public SourceQueryId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Source query id must not be blank.");
        }
    }
}
