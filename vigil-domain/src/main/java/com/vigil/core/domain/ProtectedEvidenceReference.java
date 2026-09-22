package com.vigil.core.domain;

public final class ProtectedEvidenceReference {
    private final String identifier;

    public ProtectedEvidenceReference(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Protected evidence reference must not be blank.");
        }

        this.identifier = identifier;
    }

    @Override
    public String toString() {
        return "ProtectedEvidenceReference[redacted]";
    }
}
