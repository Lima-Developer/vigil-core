package com.vigil.application.casework;

import java.util.Objects;
import java.util.UUID;

/** Request to sanitize the authorized SQL of a case and, only if safe, advance it to {@code SANITIZED}. */
public record SanitizeCaseCommand(UUID caseId, String sql, String actor) {
    public SanitizeCaseCommand {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        Objects.requireNonNull(sql, "SQL must not be null.");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Actor must not be blank.");
        }
    }
}
