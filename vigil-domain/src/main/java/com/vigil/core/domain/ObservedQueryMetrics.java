package com.vigil.core.domain;

import java.time.Duration;
import java.util.Objects;

public record ObservedQueryMetrics(
        long calls,
        Duration totalExecutionTime,
        long rows
) {
    public ObservedQueryMetrics {
        if (calls < 0) {
            throw new IllegalArgumentException("Calls must not be negative.");
        }

        if (rows < 0) {
            throw new IllegalArgumentException("Rows must not be negative.");
        }

        Objects.requireNonNull(totalExecutionTime, "Total execution time must not be null.");

        if (totalExecutionTime.isNegative()){
            throw new IllegalArgumentException("Total execution time must not be negative.");
        }

    }
}
