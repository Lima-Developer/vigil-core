package com.vigil.application.inference;

import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.SanitizationResult;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Capability object proving that inference context crossed the fail-closed sanitizer. */
public record SanitizedInferenceContext(
        SanitizationResult.Safe approval,
        List<SafeRelationReference> relations,
        List<SafeCatalogReference> catalogReferences,
        Set<UUID> allowedEvidenceIds
) {
    public SanitizedInferenceContext {
        Objects.requireNonNull(approval, "Safe sanitization approval must not be null.");
        relations = List.copyOf(Objects.requireNonNull(relations, "Relations must not be null."));
        catalogReferences = List.copyOf(Objects.requireNonNull(catalogReferences, "Catalog references must not be null."));
        allowedEvidenceIds = Set.copyOf(Objects.requireNonNull(allowedEvidenceIds, "Evidence ids must not be null."));
        if (allowedEvidenceIds.isEmpty()) throw new IllegalArgumentException("Inference requires allowed evidence ids.");
        if (relations.stream().anyMatch(Objects::isNull) || catalogReferences.stream().anyMatch(Objects::isNull)
                || allowedEvidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Approved inference references must not contain null.");
        }
    }
}
