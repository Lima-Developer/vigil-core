package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.util.Objects;

public record RelationMetadataEvidencePayload(
        SafeRelationReference relation,
        long estimatedRows,
        int indexCount,
        boolean compatibleIndexExists
) implements EvidencePayload {
    public RelationMetadataEvidencePayload {
        Objects.requireNonNull(relation, "Relation reference must not be null.");

        if (estimatedRows < 0) {
            throw new IllegalArgumentException("Estimated rows must not be negative.");
        }

        if (indexCount < 0) {
            throw new IllegalArgumentException("Index count must not be negative.");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.RELATION_METADATA;
    }
}
