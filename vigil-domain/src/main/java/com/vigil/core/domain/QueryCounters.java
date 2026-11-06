package com.vigil.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Monotonic counters for one pg_stat_statements identity; execution time is in milliseconds. */
public record QueryCounters(long dbid, long userid, long queryId, boolean topLevel, long calls,
                            BigDecimal totalExecutionMillis, long rows, long sharedBlksHit,
                            long sharedBlksRead, long tempBlksRead, long tempBlksWritten) {
    public record Key(long dbid, long userid, long queryId, boolean topLevel) {
        public Key {
            if (dbid < 0 || userid < 0) throw new IllegalArgumentException("Query identity must be non-negative except queryId.");
        }
    }

    public QueryCounters {
        Objects.requireNonNull(totalExecutionMillis, "Total execution milliseconds must not be null.");
        new Key(dbid, userid, queryId, topLevel);
        if (calls < 0 || totalExecutionMillis.signum() < 0 || rows < 0 || sharedBlksHit < 0
                || sharedBlksRead < 0 || tempBlksRead < 0 || tempBlksWritten < 0) {
            throw new IllegalArgumentException("Query counters must not be negative.");
        }
    }

    public Key key() {
        return new Key(dbid, userid, queryId, topLevel);
    }

    boolean regressedFrom(QueryCounters earlier) {
        if (!key().equals(earlier.key())) throw new IllegalArgumentException("Different query identities.");
        return calls < earlier.calls || totalExecutionMillis.compareTo(earlier.totalExecutionMillis) < 0
                || rows < earlier.rows || sharedBlksHit < earlier.sharedBlksHit
                || sharedBlksRead < earlier.sharedBlksRead || tempBlksRead < earlier.tempBlksRead
                || tempBlksWritten < earlier.tempBlksWritten;
    }

    QueryCounters minus(QueryCounters earlier) {
        if (regressedFrom(earlier)) throw new IllegalArgumentException("Counter regression.");
        return new QueryCounters(dbid, userid, queryId, topLevel, calls - earlier.calls,
                totalExecutionMillis.subtract(earlier.totalExecutionMillis), rows - earlier.rows,
                sharedBlksHit - earlier.sharedBlksHit, sharedBlksRead - earlier.sharedBlksRead,
                tempBlksRead - earlier.tempBlksRead, tempBlksWritten - earlier.tempBlksWritten);
    }
}
