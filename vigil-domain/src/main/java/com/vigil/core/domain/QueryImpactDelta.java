package com.vigil.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The deltas of one query family over an interval, plus its share of the workspace total execution time in the same
 * interval. Absolute impact is the total execution time, never the number of calls alone.
 */
public record QueryImpactDelta(long calls, BigDecimal totalExecutionMillis, long rows, long sharedBlocksHit,
                               long sharedBlocksRead, BigDecimal workspaceShare) {
    public QueryImpactDelta {
        Objects.requireNonNull(totalExecutionMillis, "Total execution time must not be null.");
        Objects.requireNonNull(workspaceShare, "Workspace share must not be null.");
        if (calls < 0 || rows < 0 || sharedBlocksHit < 0 || sharedBlocksRead < 0) {
            throw new IllegalArgumentException("Counters must not be negative.");
        }
        if (totalExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Total execution time must not be negative.");
        }
        if (workspaceShare.signum() < 0 || workspaceShare.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Workspace share must be between 0 and 1.");
        }
    }
}
