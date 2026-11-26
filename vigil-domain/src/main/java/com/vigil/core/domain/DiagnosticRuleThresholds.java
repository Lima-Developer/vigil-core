package com.vigil.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Version-one defaults: at least 10k estimated rows and at most 10% selectivity. */
public record DiagnosticRuleThresholds(long minimumRelationRows, BigDecimal maximumSelectiveRatio) {
    public static final DiagnosticRuleThresholds DEFAULTS = new DiagnosticRuleThresholds(10_000, new BigDecimal("0.10"));

    public DiagnosticRuleThresholds {
        Objects.requireNonNull(maximumSelectiveRatio, "Maximum selective ratio must not be null.");
        if (minimumRelationRows < 1) throw new IllegalArgumentException("Minimum relation rows must be positive.");
        if (maximumSelectiveRatio.signum() <= 0 || maximumSelectiveRatio.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Maximum selective ratio must be between zero and one.");
        }
    }
}
