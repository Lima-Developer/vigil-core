package com.vigil.application.port.out.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading of the statement statistics. {@code statsReset} and {@code dealloc} describe the whole view, so the
 * caller can tell a reset or an eviction from a regular increase. {@code fullVisibility} is false when the role cannot
 * see the statements of other users, in which case {@code queries} is incomplete. {@code skippedRows} counts entries
 * discarded because they were unusable.
 */
public record TelemetryCollection(Instant collectedAt, Optional<Instant> statsReset, long dealloc,
                                  boolean fullVisibility, List<CumulativeQueryCounters> queries, int skippedRows) {
    public TelemetryCollection {
        Objects.requireNonNull(collectedAt, "Collected at must not be null.");
        Objects.requireNonNull(statsReset, "Stats reset must not be null.");
        if (dealloc < 0) throw new IllegalArgumentException("Dealloc must not be negative.");
        queries = List.copyOf(Objects.requireNonNull(queries, "Queries must not be null."));
        if (skippedRows < 0) throw new IllegalArgumentException("Skipped rows must not be negative.");
    }
}
