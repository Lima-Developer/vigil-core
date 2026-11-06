package com.vigil.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Delta for one query in an observation window (start exclusive, end inclusive). */
public record QueryWindowMetrics(Status status, Optional<ObservationWindow> window,
                                 Optional<QueryCounters> delta, Optional<BigDecimal> meanExecutionMillis) {
    public enum Status { COMPLETE, NO_BASELINE, ENTRY_NEW, ENTRY_MISSING, ENTRY_ABSENT, INVALID_WINDOW, RESET, EVICTION, INCOMPLETE }

    public QueryWindowMetrics {
        Objects.requireNonNull(status, "Status must not be null.");
        Objects.requireNonNull(window, "Window optional must not be null.");
        Objects.requireNonNull(delta, "Delta optional must not be null.");
        Objects.requireNonNull(meanExecutionMillis, "Mean optional must not be null.");
        if (status == Status.COMPLETE) {
            if (window.isEmpty() || delta.isEmpty()) throw new IllegalArgumentException("Complete window needs a delta.");
            Optional<BigDecimal> expectedMean = meanFor(delta.orElseThrow());
            if (!meanExecutionMillis.equals(expectedMean)) {
                throw new IllegalArgumentException("Mean must equal total execution milliseconds divided by calls at scale 6.");
            }
        } else if (delta.isPresent() || meanExecutionMillis.isPresent()) {
            throw new IllegalArgumentException("Incomplete windows cannot have numeric deltas.");
        }
        if (status == Status.NO_BASELINE || status == Status.INVALID_WINDOW) {
            if (window.isPresent()) throw new IllegalArgumentException("This status cannot have an observation window.");
        } else if (window.isEmpty()) {
            throw new IllegalArgumentException("This status requires an observation window.");
        }
    }

    /**
     * Calculates a pure delta for a complete pair of cumulative readings. Absence of a prior snapshot is a baseline,
     * distinct from a valid zero delta. Unknown/partial/reset/evicted windows never emit negative or guessed metrics.
     */
    public static QueryWindowMetrics between(Optional<TelemetrySnapshot> previous, TelemetrySnapshot current,
                                             QueryCounters.Key key) {
        Objects.requireNonNull(previous, "Previous snapshot optional must not be null.");
        Objects.requireNonNull(current, "Current snapshot must not be null.");
        Objects.requireNonNull(key, "Query identity must not be null.");
        return compare(previous, current).resultFor(key);
    }

    /** Computes all observed identities in one pass, including entries that appear or disappear. */
    public static Map<QueryCounters.Key, QueryWindowMetrics> betweenAll(Optional<TelemetrySnapshot> previous,
                                                                         TelemetrySnapshot current) {
        Objects.requireNonNull(previous, "Previous snapshot optional must not be null.");
        Objects.requireNonNull(current, "Current snapshot must not be null.");
        Comparison comparison = compare(previous, current);
        Map<QueryCounters.Key, QueryWindowMetrics> results = new LinkedHashMap<>();
        comparison.before().keySet().forEach(key -> results.put(key, comparison.resultFor(key)));
        comparison.after().keySet().forEach(key -> {
            if (!results.containsKey(key)) results.put(key, comparison.resultFor(key));
        });
        return Collections.unmodifiableMap(results);
    }

    private static Comparison compare(Optional<TelemetrySnapshot> previous, TelemetrySnapshot current) {
        Map<QueryCounters.Key, QueryCounters> after = index(current);
        if (previous.isEmpty()) {
            return new Comparison(Status.NO_BASELINE, Optional.empty(), Map.of(), after);
        }
        TelemetrySnapshot before = previous.orElseThrow();
        Map<QueryCounters.Key, QueryCounters> prior = index(before);
        if (!current.collectedAt().isAfter(before.collectedAt())) {
            return new Comparison(Status.INVALID_WINDOW, Optional.empty(), prior, after);
        }
        Optional<ObservationWindow> window = Optional.of(new ObservationWindow(before.collectedAt(), current.collectedAt()));
        if (!before.fullVisibility() || !current.fullVisibility() || before.skippedRows() > 0 || current.skippedRows() > 0) {
            return new Comparison(Status.INCOMPLETE, window, prior, after);
        }
        if (!before.statsReset().equals(current.statsReset()) || current.dealloc() < before.dealloc()) {
            return new Comparison(Status.RESET, window, prior, after);
        }
        if (current.dealloc() > before.dealloc()) return new Comparison(Status.EVICTION, window, prior, after);
        for (var entry : prior.entrySet()) {
            QueryCounters latest = after.get(entry.getKey());
            if (latest != null && latest.regressedFrom(entry.getValue())) {
                return new Comparison(Status.RESET, window, prior, after);
            }
        }
        return new Comparison(null, window, prior, after);
    }

    private static Map<QueryCounters.Key, QueryCounters> index(TelemetrySnapshot snapshot) {
        Map<QueryCounters.Key, QueryCounters> indexed = new LinkedHashMap<>();
        for (QueryCounters query : snapshot.queries()) indexed.put(query.key(), query);
        return indexed;
    }

    private record Comparison(Status invalidStatus, Optional<ObservationWindow> window,
                              Map<QueryCounters.Key, QueryCounters> before,
                              Map<QueryCounters.Key, QueryCounters> after) {
        QueryWindowMetrics resultFor(QueryCounters.Key key) {
            if (invalidStatus != null) return unavailable(invalidStatus, window);
            QueryCounters old = before.get(key);
            QueryCounters latest = after.get(key);
            if (old == null && latest == null) return unavailable(Status.ENTRY_ABSENT, window);
            if (latest == null) return unavailable(Status.ENTRY_MISSING, window);
            if (old == null) return unavailable(Status.ENTRY_NEW, window);
            QueryCounters delta = latest.minus(old);
            return new QueryWindowMetrics(Status.COMPLETE, window, Optional.of(delta), meanFor(delta));
        }
    }

    /** Mean execution milliseconds per call, rounded to six decimal places with HALF_EVEN. */
    private static Optional<BigDecimal> meanFor(QueryCounters delta) {
        if (delta.calls() == 0) return Optional.empty();
        return Optional.of(delta.totalExecutionMillis()
                .divide(BigDecimal.valueOf(delta.calls()), 6, RoundingMode.HALF_EVEN));
    }

    private static QueryWindowMetrics unavailable(Status status, Optional<ObservationWindow> window) {
        return new QueryWindowMetrics(status, window, Optional.empty(), Optional.empty());
    }
}
