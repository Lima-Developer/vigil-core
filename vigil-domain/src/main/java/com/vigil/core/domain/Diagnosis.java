package com.vigil.core.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Evidence-grounded diagnosis; it cannot exist without at least one deterministic finding. */
public record Diagnosis(List<Finding> findings, Set<UUID> evidenceIds) {
    public Diagnosis {
        findings = List.copyOf(Objects.requireNonNull(findings, "Findings must not be null."));
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "Evidence ids must not be null."));
        if (findings.isEmpty()) throw new IllegalArgumentException("A diagnosis requires at least one finding.");
        if (findings.stream().anyMatch(Objects::isNull) || evidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Diagnosis references must not contain null.");
        }
        var referenced = new LinkedHashSet<UUID>();
        findings.forEach(finding -> referenced.addAll(finding.evidenceIds()));
        if (!evidenceIds.containsAll(referenced)) {
            throw new IllegalArgumentException("Diagnosis must include every evidence referenced by its findings.");
        }
    }
}
