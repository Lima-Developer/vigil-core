package com.vigil.application.casework;

import java.util.UUID;

public record TriageAnalysisCaseCommand(UUID caseId, String reason, String actor) {
    public TriageAnalysisCaseCommand {
        if (caseId == null) throw new IllegalArgumentException("Case id must not be null.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Reason must not be blank.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
    }
}
