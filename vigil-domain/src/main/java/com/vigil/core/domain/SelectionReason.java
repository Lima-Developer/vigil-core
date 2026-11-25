package com.vigil.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Why a family was selected: a code plus the numbers that justify it. The human-readable text is generated from those
 * numbers and <strong>never</strong> contains SQL or any literal of the query — only impact metrics.
 */
public record SelectionReason(Code code, BigDecimal totalExecutionMillis, long calls, BigDecimal workspaceShare) {

    public enum Code { TOP_TOTAL_EXEC_TIME }

    public SelectionReason {
        Objects.requireNonNull(code, "Code must not be null.");
        Objects.requireNonNull(totalExecutionMillis, "Total execution time must not be null.");
        Objects.requireNonNull(workspaceShare, "Workspace share must not be null.");
        if (calls < 0 || totalExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Impact metrics must not be negative.");
        }
    }

    /** Stable, literal-free rendering, for example "maior tempo total no intervalo: 1234 ms em 56 chamadas (41% do tempo do workspace)". */
    public String render() {
        long millis = totalExecutionMillis.setScale(0, RoundingMode.HALF_UP).longValueExact();
        int percent = workspaceShare.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return switch (code) {
            case TOP_TOTAL_EXEC_TIME -> "maior tempo total no intervalo: " + millis + " ms em " + calls
                    + " chamadas (" + percent + "% do tempo do workspace)";
        };
    }
}
