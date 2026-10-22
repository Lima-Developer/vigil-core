package com.vigil.application.port.out.interfaces;

import java.time.Instant;

/**
 * Reads the cumulative per-statement counters of the monitored target. Values are raw: deltas, baselines and resets
 * are the concern of the caller.
 */
public interface QueryTelemetryPort {
    TelemetryCollection collect(Instant collectedAt);
}
