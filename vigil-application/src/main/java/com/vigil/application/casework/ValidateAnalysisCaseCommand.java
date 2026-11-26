package com.vigil.application.casework;

import com.vigil.core.domain.enums.QueryShape;
import java.util.UUID;

/** Starts the automatic, evidence-grounded classification of one validating case. */
public record ValidateAnalysisCaseCommand(UUID caseId, String actor, QueryShape queryShape) {
    public ValidateAnalysisCaseCommand(UUID caseId, String actor) {
        this(caseId, actor, QueryShape.SIMPLE_SELECT);
    }

    public ValidateAnalysisCaseCommand {
        if (caseId == null) throw new IllegalArgumentException("Case id must not be null.");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor must not be blank.");
        if (queryShape == null) throw new IllegalArgumentException("Query shape must not be null.");
    }
}
