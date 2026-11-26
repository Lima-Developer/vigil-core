package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.util.Objects;
import java.util.Optional;

public record RelationMetadataEvidencePayload(
        SafeRelationReference relation,
        long estimatedRows,
        int indexCount,
        boolean compatibleIndexExists,
        Optional<RelationBlueprint> blueprint
) implements EvidencePayload {
    public RelationMetadataEvidencePayload(SafeRelationReference relation, long estimatedRows, int indexCount,
                                           boolean compatibleIndexExists) {
        this(relation, estimatedRows, indexCount, compatibleIndexExists, Optional.empty());
    }

    public RelationMetadataEvidencePayload {
        Objects.requireNonNull(relation, "Relation reference must not be null.");

        if (estimatedRows < 0) {
            throw new IllegalArgumentException("Estimated rows must not be negative.");
        }

        if (indexCount < 0) {
            throw new IllegalArgumentException("Index count must not be negative.");
        }
        Objects.requireNonNull(blueprint, "Relation blueprint must not be null.");
        blueprint.ifPresent(value -> {
            if (!value.relation().equals(relation))
                throw new IllegalArgumentException("Blueprint relation must match metadata relation.");
            if (!value.columns().isEmpty() || !value.constraints().isEmpty())
                throw new IllegalArgumentException("Diagnostic evidence carries only size, indexes and index keys.");
        });
    }

    public static RelationMetadataEvidencePayload fromBlueprint(RelationBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "Relation blueprint must not be null.");
        long rows = blueprint.estimatedRows().orElse(0);
        return new RelationMetadataEvidencePayload(blueprint.relation(), rows, blueprint.indexes().size(), false,
                Optional.of(blueprint));
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.RELATION_METADATA;
    }
}
