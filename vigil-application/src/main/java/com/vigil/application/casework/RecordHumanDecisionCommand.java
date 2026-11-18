package com.vigil.application.casework;

import java.util.Objects;
import java.util.UUID;

/** Explicit command; a human decision is recorded, never executed against the monitored database. */
public record RecordHumanDecisionCommand(UUID caseId, HumanDecision decision, String reason, String actor) {
    public RecordHumanDecisionCommand {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        Objects.requireNonNull(decision, "Decision must not be null.");
        if (reason == null || reason.trim().length() < 8) {
            throw new IllegalArgumentException("Justificativa deve ter ao menos 8 caracteres");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Actor must not be blank.");
        }
        reason = reason.trim();
    }
}
