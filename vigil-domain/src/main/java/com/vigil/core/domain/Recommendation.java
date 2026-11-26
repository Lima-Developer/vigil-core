package com.vigil.core.domain;

import com.vigil.core.domain.enums.RecommendationLimitation;
import com.vigil.core.domain.enums.RecommendationRisk;
import com.vigil.core.domain.enums.ValidationOutcome;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Versioned technical material presented to a human; it never applies a database change. */
public record Recommendation(
        long version,
        OptimizationHypothesis hypothesis,
        ValidationOutcome validationOutcome,
        Set<UUID> evidenceIds,
        List<RecommendationRisk> risks,
        List<RecommendationLimitation> limitations
) {
    public Recommendation {
        if (version < 1) throw new IllegalArgumentException("Recommendation version starts at one.");
        Objects.requireNonNull(hypothesis, "Recommendation hypothesis must not be null.");
        Objects.requireNonNull(validationOutcome, "Validation outcome must not be null.");
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "Evidence ids must not be null."));
        risks = List.copyOf(Objects.requireNonNull(risks, "Risks must not be null."));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "Limitations must not be null."));
        if (evidenceIds.isEmpty() || risks.isEmpty() || limitations.isEmpty()) {
            throw new IllegalArgumentException("Recommendation requires evidence, risks and limitations.");
        }
        if (!evidenceIds.containsAll(hypothesis.origin().evidenceIds())) {
            throw new IllegalArgumentException("Recommendation must include the hypothesis evidence.");
        }
    }
}
