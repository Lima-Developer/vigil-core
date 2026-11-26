package com.vigil.core.domain;

import com.vigil.core.domain.enums.DiagnosticRuleCode;
import com.vigil.core.domain.enums.FindingSeverity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A deterministic rule result grounded only in evidence that belongs to the case. */
public record Finding(
        DiagnosticRuleCode ruleCode,
        FindingSeverity severity,
        List<UUID> evidenceIds,
        String explanation
) {
    public Finding {
        Objects.requireNonNull(ruleCode, "Rule code must not be null.");
        Objects.requireNonNull(severity, "Severity must not be null.");
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "Evidence ids must not be null."));
        if (evidenceIds.isEmpty() || evidenceIds.stream().anyMatch(Objects::isNull)
                || evidenceIds.stream().distinct().count() != evidenceIds.size()) {
            throw new IllegalArgumentException("A finding must reference distinct evidence ids.");
        }
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("Finding explanation must not be blank.");
        }
        explanation = explanation.strip();
    }
}
