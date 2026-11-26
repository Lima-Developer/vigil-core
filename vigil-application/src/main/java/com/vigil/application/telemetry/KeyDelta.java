package com.vigil.application.telemetry;

import java.math.BigDecimal;

/**
 * The delta of one pg_stat_statements entry, identified by its native key, between two snapshots. Used by the impact
 * selection path; the workspace-level {@link AggregatedTelemetryDelta} is untouched.
 */
public record KeyDelta(long dbid, long userid, long queryId, boolean topLevel, long calls,
                       BigDecimal executionMillis, long rows, long sharedHits, long sharedReads) {
}
