package com.vigil.core.domain;

import com.vigil.core.domain.enums.DiagnosticInconclusiveReason;

import java.util.List;
import java.util.Objects;

public record DiagnosticEvaluation(List<Finding> findings, List<DiagnosticInconclusiveReason> inconclusiveReasons) {
    public DiagnosticEvaluation {
        findings = List.copyOf(Objects.requireNonNull(findings, "Findings must not be null."));
        inconclusiveReasons = List.copyOf(Objects.requireNonNull(inconclusiveReasons, "Reasons must not be null."));
    }
}
