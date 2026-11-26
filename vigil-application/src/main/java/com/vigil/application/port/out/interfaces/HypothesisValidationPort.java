package com.vigil.application.port.out.interfaces;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.enums.ValidationOutcome;
import com.vigil.core.domain.enums.FidelityLevel;

/**
 * Evaluates a bounded optimization hypothesis in an isolated laboratory and returns
 * a classification that can be recorded by the case workflow. Implementations must
 * never execute DDL or DML against the monitored target.
 */
public interface HypothesisValidationPort {
    ValidationAssessment validate(AnalysisCase analysisCase);

    record ValidationAssessment(ValidationOutcome outcome, String rationale, FidelityLevel fidelityLevel) {
        public ValidationAssessment(ValidationOutcome outcome, String rationale) {
            this(outcome, rationale, FidelityLevel.SYNTHETIC_PHYSICAL_INDEX);
        }

        public ValidationAssessment {
            if (outcome == null) throw new IllegalArgumentException("Validation outcome must not be null.");
            if (rationale == null || rationale.isBlank())
                throw new IllegalArgumentException("Validation rationale must not be blank.");
            if (fidelityLevel == null) throw new IllegalArgumentException("Fidelity level must not be null.");
        }
    }
}
