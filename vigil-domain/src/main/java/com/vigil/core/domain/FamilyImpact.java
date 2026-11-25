package com.vigil.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated deltas of one query family (one {@link QueryFingerprint}) over an interval, summed across every source
 * that shares the fingerprint. Input to {@link ImpactSelectionPolicy}; carries no SQL.
 */
public record FamilyImpact(QueryFingerprint fingerprint, long calls, BigDecimal totalExecutionMillis, long rows,
                           long sharedBlocksHit, long sharedBlocksRead, List<SourceQueryId> sourceQueryIds) {
    public FamilyImpact {
        Objects.requireNonNull(fingerprint, "Fingerprint must not be null.");
        Objects.requireNonNull(totalExecutionMillis, "Total execution time must not be null.");
        sourceQueryIds = List.copyOf(Objects.requireNonNull(sourceQueryIds, "Source query ids must not be null."));
        if (sourceQueryIds.isEmpty()) {
            throw new IllegalArgumentException("A family has at least one source query id.");
        }
        if (calls < 0 || rows < 0 || sharedBlocksHit < 0 || sharedBlocksRead < 0
                || totalExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Counters must not be negative.");
        }
    }
}
