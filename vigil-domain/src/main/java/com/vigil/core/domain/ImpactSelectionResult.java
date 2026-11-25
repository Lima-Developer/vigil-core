package com.vigil.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of applying {@link ImpactSelectionPolicy}. An empty candidate list is valid and always carries a reason:
 * {@link Outcome#NO_CANDIDATES_ABOVE_THRESHOLDS} or {@link Outcome#WARMING}. {@code unclassifiedCount} reports how
 * many keys were dropped because their SQL could not be fingerprinted (fail-closed), never their content.
 */
public record ImpactSelectionResult(List<Candidate> candidates, int unclassifiedCount, Outcome outcome) {

    public enum Outcome { SELECTED, NO_CANDIDATES_ABOVE_THRESHOLDS, WARMING }

    public ImpactSelectionResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "Candidates must not be null."));
        Objects.requireNonNull(outcome, "Outcome must not be null.");
        if (unclassifiedCount < 0) {
            throw new IllegalArgumentException("Unclassified count must not be negative.");
        }
        boolean selected = outcome == Outcome.SELECTED;
        if (selected == candidates.isEmpty()) {
            throw new IllegalArgumentException("SELECTED has candidates; the other outcomes are empty.");
        }
    }

    public static ImpactSelectionResult selected(List<Candidate> candidates, int unclassifiedCount) {
        return new ImpactSelectionResult(candidates, unclassifiedCount, Outcome.SELECTED);
    }

    public static ImpactSelectionResult none(int unclassifiedCount) {
        return new ImpactSelectionResult(List.of(), unclassifiedCount, Outcome.NO_CANDIDATES_ABOVE_THRESHOLDS);
    }

    public static ImpactSelectionResult warming(int unclassifiedCount) {
        return new ImpactSelectionResult(List.of(), unclassifiedCount, Outcome.WARMING);
    }
}
