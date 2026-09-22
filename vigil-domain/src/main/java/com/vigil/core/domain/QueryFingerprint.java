package com.vigil.core.domain;

public record QueryFingerprint (
   String value,
   String algorithmVersion
) {
    public QueryFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value must not be blank.");
        }

        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("Reason must not be blank.");
        }
    }
}
