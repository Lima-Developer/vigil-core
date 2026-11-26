package com.vigil.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A query family proposed for investigation. It represents the decision to investigate, not the confirmation that the
 * query is inadequate — impact precedes diagnosis. The family identity is the {@link QueryFingerprint} (RF11); the
 * {@link SourceQueryId}s are only the origins that make it up.
 */
public record Candidate(QueryFingerprint fingerprint, List<SourceQueryId> sourceQueryIds,
                        ImpactAssessment assessment, SelectionReason selectionReason) {
    public Candidate {
        Objects.requireNonNull(fingerprint, "Fingerprint must not be null.");
        Objects.requireNonNull(assessment, "Assessment must not be null.");
        Objects.requireNonNull(selectionReason, "Selection reason must not be null.");
        sourceQueryIds = List.copyOf(Objects.requireNonNull(sourceQueryIds, "Source query ids must not be null."));
        if (sourceQueryIds.isEmpty()) {
            throw new IllegalArgumentException("A candidate family has at least one source query id.");
        }
    }
}
