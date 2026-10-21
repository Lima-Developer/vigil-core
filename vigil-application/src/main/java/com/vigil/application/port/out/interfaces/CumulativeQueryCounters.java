package com.vigil.application.port.out.interfaces;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Counters accumulated since the last statistics reset for one entry identified by
 * {@code (dbid, userid, queryId, topLevel)}. Execution time is kept in milliseconds, exactly as reported. No query
 * text is carried.
 */
public record CumulativeQueryCounters(long dbid, long userid, long queryId, boolean topLevel, long calls,
                                      BigDecimal totalExecutionMillis, long rows, long sharedBlksHit,
                                      long sharedBlksRead, long tempBlksRead, long tempBlksWritten) {
    public CumulativeQueryCounters {
        Objects.requireNonNull(totalExecutionMillis, "Total execution time must not be null.");
        if (dbid < 0) throw new IllegalArgumentException("Dbid must not be negative.");
        if (userid < 0) throw new IllegalArgumentException("Userid must not be negative.");
        if (calls < 0) throw new IllegalArgumentException("Calls must not be negative.");
        if (totalExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Total execution time must not be negative.");
        }
        if (rows < 0) throw new IllegalArgumentException("Rows must not be negative.");
        if (sharedBlksHit < 0) throw new IllegalArgumentException("Shared blocks hit must not be negative.");
        if (sharedBlksRead < 0) throw new IllegalArgumentException("Shared blocks read must not be negative.");
        if (tempBlksRead < 0) throw new IllegalArgumentException("Temp blocks read must not be negative.");
        if (tempBlksWritten < 0) throw new IllegalArgumentException("Temp blocks written must not be negative.");
    }
}
