package com.vigil.core.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One immutable cumulative reading. No SQL text crosses this domain boundary. */
public record TelemetrySnapshot(Instant collectedAt, Optional<Instant> statsReset, long dealloc,
                                boolean fullVisibility, int skippedRows, List<QueryCounters> queries) {
    public TelemetrySnapshot {
        Objects.requireNonNull(collectedAt, "Collection time must not be null.");
        Objects.requireNonNull(statsReset, "Stats reset optional must not be null.");
        queries = List.copyOf(Objects.requireNonNull(queries, "Queries must not be null."));
        if (dealloc < 0 || skippedRows < 0) throw new IllegalArgumentException("Collection counters must not be negative.");
        Set<QueryCounters.Key> keys = new HashSet<>();
        for (QueryCounters query : queries) {
            if (!keys.add(query.key())) throw new IllegalArgumentException("Duplicate query identity.");
        }
    }

    public Optional<QueryCounters> query(QueryCounters.Key key) {
        Objects.requireNonNull(key, "Query identity must not be null.");
        return queries.stream().filter(query -> query.key().equals(key)).findFirst();
    }
}
