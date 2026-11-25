package com.vigil.application.port.out.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading for the impact selection path: the same whole-view fields as {@link TelemetryCollection} plus, per
 * entry, the query-family fingerprint (or empty for UNCLASSIFIED). {@link #toCollection()} projects it back to a plain
 * {@link TelemetryCollection} so the existing delta calculator (and its warming rules) can be reused unchanged.
 */
public record ImpactTelemetrySnapshot(Instant collectedAt, Optional<Instant> statsReset, long dealloc,
                                      boolean fullVisibility, List<FingerprintedQueryCounters> entries,
                                      int skippedRows) {
    public ImpactTelemetrySnapshot {
        Objects.requireNonNull(collectedAt, "Collected at must not be null.");
        Objects.requireNonNull(statsReset, "Stats reset must not be null.");
        entries = List.copyOf(Objects.requireNonNull(entries, "Entries must not be null."));
        if (dealloc < 0) throw new IllegalArgumentException("Dealloc must not be negative.");
        if (skippedRows < 0) throw new IllegalArgumentException("Skipped rows must not be negative.");
    }

    public TelemetryCollection toCollection() {
        return new TelemetryCollection(collectedAt, statsReset, dealloc, fullVisibility,
                entries.stream().map(FingerprintedQueryCounters::counters).toList(), skippedRows);
    }
}
