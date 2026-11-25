package com.vigil.core.domain;

import java.util.Objects;

/**
 * The evaluated impact of one query family: its deltas and share (in {@link QueryImpactDelta}), its position in the
 * ranking and the baseline confidence. In the current scope there is no baseline, so this is always
 * {@link BaselineConfidence#ABSENT}. A high-impact family can still become a Candidate without a baseline.
 */
public record ImpactAssessment(QueryImpactDelta delta, int rank, BaselineConfidence baselineConfidence) {
    public ImpactAssessment {
        Objects.requireNonNull(delta, "Delta must not be null.");
        Objects.requireNonNull(baselineConfidence, "Baseline confidence must not be null.");
        if (rank < 1) {
            throw new IllegalArgumentException("Rank starts at 1.");
        }
    }
}
