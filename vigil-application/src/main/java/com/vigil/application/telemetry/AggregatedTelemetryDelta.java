package com.vigil.application.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AggregatedTelemetryDelta(Instant start, Instant end, long calls, BigDecimal executionMillis,
                                       long rows, long sharedHits, long sharedReads, long tempReads,
                                       long tempWrites, String quality, String reason) {
    public AggregatedTelemetryDelta {
        Objects.requireNonNull(start); Objects.requireNonNull(end); Objects.requireNonNull(executionMillis);
        Objects.requireNonNull(quality);
        if (!start.isBefore(end) || calls < 0 || executionMillis.signum() < 0 || rows < 0
                || sharedHits < 0 || sharedReads < 0 || tempReads < 0 || tempWrites < 0)
            throw new IllegalArgumentException("Invalid telemetry delta");
        if (!(quality.equals("measured") || quality.equals("partial")))
            throw new IllegalArgumentException("Invalid quality");
        if (quality.equals("measured") && reason != null) throw new IllegalArgumentException("Measured delta has reason");
        if (quality.equals("partial") && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("Partial delta needs reason");
    }

    public BigDecimal meanExecutionMillis() {
        return calls == 0 ? null : executionMillis.divide(BigDecimal.valueOf(calls), 6, java.math.RoundingMode.HALF_UP);
    }
}
