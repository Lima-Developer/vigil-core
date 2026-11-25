package com.vigil.application.telemetry;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Converts two monotonic snapshots into one aggregate without inventing data across resets or eviction. */
public final class QueryTelemetryDeltaCalculator {
    public QueryTelemetryDeltaResult calculate(TelemetryCollection previous, TelemetryCollection current) {
        if (previous == null) return QueryTelemetryDeltaResult.warming("FIRST_SAMPLE");
        if (!previous.collectedAt().isBefore(current.collectedAt())) return QueryTelemetryDeltaResult.warming("NON_MONOTONIC_TIME");
        if (!previous.statsReset().equals(current.statsReset())) return QueryTelemetryDeltaResult.warming("STATS_RESET_CHANGED");
        if (previous.dealloc() != current.dealloc()) return QueryTelemetryDeltaResult.warming("ENTRIES_DEALLOCATED");
        var before = index(previous); var after = index(current);
        var shared = new HashSet<>(before.keySet()); shared.retainAll(after.keySet());
        for (var key : shared) if (regressed(before.get(key), after.get(key)))
            return QueryTelemetryDeltaResult.warming("COUNTER_REGRESSION");
        long calls=0, rows=0, hits=0, reads=0, tempReads=0, tempWrites=0;
        var execution=BigDecimal.ZERO;
        for (var key : shared) {
            var a=before.get(key); var b=after.get(key);
            calls += b.calls()-a.calls(); rows += b.rows()-a.rows();
            hits += b.sharedBlksHit()-a.sharedBlksHit(); reads += b.sharedBlksRead()-a.sharedBlksRead();
            tempReads += b.tempBlksRead()-a.tempBlksRead(); tempWrites += b.tempBlksWritten()-a.tempBlksWritten();
            execution = execution.add(b.totalExecutionMillis().subtract(a.totalExecutionMillis()));
        }
        boolean partial=!previous.fullVisibility() || !current.fullVisibility()
                || previous.skippedRows()>0 || current.skippedRows()>0
                || !before.keySet().equals(after.keySet());
        String reason=partial?"PARTIAL_VISIBILITY_OR_ENTRY_CHURN":null;
        return QueryTelemetryDeltaResult.available(new AggregatedTelemetryDelta(previous.collectedAt(), current.collectedAt(),
                calls, execution, rows, hits, reads, tempReads, tempWrites, partial?"partial":"measured", reason));
    }

    /**
     * Exposes the same deltas per {@link QueryCounterKey} that {@link #calculate} sums into the workspace total,
     * applying the identical warming rules (first sample, non-monotonic time, stats reset, eviction, regression).
     * It does not touch {@link AggregatedTelemetryDelta} or {@link #calculate}, so their tests are unaffected.
     */
    public PerKeyDeltaResult perKeyDeltas(TelemetryCollection previous, TelemetryCollection current) {
        if (previous == null) return PerKeyDeltaResult.warming("FIRST_SAMPLE");
        if (!previous.collectedAt().isBefore(current.collectedAt())) return PerKeyDeltaResult.warming("NON_MONOTONIC_TIME");
        if (!previous.statsReset().equals(current.statsReset())) return PerKeyDeltaResult.warming("STATS_RESET_CHANGED");
        if (previous.dealloc() != current.dealloc()) return PerKeyDeltaResult.warming("ENTRIES_DEALLOCATED");
        var before = index(previous); var after = index(current);
        var shared = new HashSet<>(before.keySet()); shared.retainAll(after.keySet());
        for (var key : shared) if (regressed(before.get(key), after.get(key)))
            return PerKeyDeltaResult.warming("COUNTER_REGRESSION");
        var deltas = new ArrayList<KeyDelta>();
        for (var key : shared) {
            var a = before.get(key); var b = after.get(key);
            deltas.add(new KeyDelta(key.dbid(), key.userid(), key.queryId(), key.topLevel(),
                    b.calls() - a.calls(), b.totalExecutionMillis().subtract(a.totalExecutionMillis()),
                    b.rows() - a.rows(), b.sharedBlksHit() - a.sharedBlksHit(), b.sharedBlksRead() - a.sharedBlksRead()));
        }
        boolean partial = !previous.fullVisibility() || !current.fullVisibility()
                || previous.skippedRows() > 0 || current.skippedRows() > 0
                || !before.keySet().equals(after.keySet());
        return PerKeyDeltaResult.available(List.copyOf(deltas), partial);
    }

    private static Map<QueryCounterKey,CumulativeQueryCounters> index(TelemetryCollection collection) {
        var result=new HashMap<QueryCounterKey,CumulativeQueryCounters>();
        for (var item:collection.queries()) result.put(new QueryCounterKey(item.dbid(),item.userid(),item.queryId(),item.topLevel()),item);
        return result;
    }
    private static boolean regressed(CumulativeQueryCounters a,CumulativeQueryCounters b) {
        return b.calls()<a.calls() || b.totalExecutionMillis().compareTo(a.totalExecutionMillis())<0 || b.rows()<a.rows()
                || b.sharedBlksHit()<a.sharedBlksHit() || b.sharedBlksRead()<a.sharedBlksRead()
                || b.tempBlksRead()<a.tempBlksRead() || b.tempBlksWritten()<a.tempBlksWritten();
    }
}
