package com.vigil.application.casework;

import java.util.UUID;

/** Starts the automatic, evidence-grounded classification of one validating case. */
public record ValidateAnalysisCaseCommand(UUID caseId, String actor) {
    public ValidateAnalysisCaseCommand {
        if (caseId == null) throw new IllegalArgumentException("Case id must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
    }
}
