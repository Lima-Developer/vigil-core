package com.vigil.core.domain;

public final class ProtectedEvidenceReference {
    private final String identifier;

    public ProtectedEvidenceReference(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Protected evidence reference must not be blank.");
        }

        this.identifier = identifier;
    }

    /** Opaque identifier for persistence only; never log it. Does not resolve the referenced artifact. */
    public String opaqueIdentifier() {
        return identifier;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtectedEvidenceReference reference && identifier.equals(reference.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return "ProtectedEvidenceReference[redacted]";
    }
}
