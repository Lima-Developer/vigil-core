package com.vigil.application.casework;

import com.vigil.core.domain.OptimizationHypothesis;

import java.util.Objects;
import java.util.UUID;

public record ProposeHypothesisCommand(UUID caseId, OptimizationHypothesis hypothesis, String actor) {
    public ProposeHypothesisCommand {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        Objects.requireNonNull(hypothesis, "Hypothesis must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
    }
}
