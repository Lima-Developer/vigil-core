package com.vigil.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        if (previous.isEmpty()) return unavailable(Status.NO_BASELINE, Optional.empty());
        TelemetrySnapshot before = previous.orElseThrow();
        if (!current.collectedAt().isAfter(before.collectedAt())) {
            return unavailable(Status.INVALID_WINDOW, Optional.empty());
        }
        Optional<ObservationWindow> window = Optional.of(new ObservationWindow(before.collectedAt(), current.collectedAt()));
        if (!before.fullVisibility() || !current.fullVisibility() || before.skippedRows() > 0 || current.skippedRows() > 0) {
            return unavailable(Status.INCOMPLETE, window);
        }
        if (!before.statsReset().equals(current.statsReset()) || current.dealloc() < before.dealloc()) {
            return unavailable(Status.RESET, window);
        }
        if (current.dealloc() > before.dealloc()) return unavailable(Status.EVICTION, window);
        for (QueryCounters old : before.queries()) {
            Optional<QueryCounters> latest = current.query(old.key());
            if (latest.isPresent() && latest.orElseThrow().regressedFrom(old)) {
                return unavailable(Status.RESET, window);
            }
        }
        Optional<QueryCounters> old = before.query(key);
        Optional<QueryCounters> latest = current.query(key);
        if (old.isEmpty() && latest.isEmpty()) return unavailable(Status.ENTRY_ABSENT, window);
        if (latest.isEmpty()) return unavailable(Status.ENTRY_MISSING, window);
        if (old.isEmpty()) return unavailable(Status.ENTRY_NEW, window);
        QueryCounters delta = latest.orElseThrow().minus(old.orElseThrow());
        return new QueryWindowMetrics(Status.COMPLETE, window, Optional.of(delta), meanFor(delta));
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
