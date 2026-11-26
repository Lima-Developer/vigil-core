package com.vigil.application.casework;

import com.vigil.core.domain.ImpactAssessment;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DiagnoseCaseCommand(UUID caseId, Optional<ImpactAssessment> selectedImpact, String actor) {
    public DiagnoseCaseCommand {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        Objects.requireNonNull(selectedImpact, "Selected impact must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
    }
}
