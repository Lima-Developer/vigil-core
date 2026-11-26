package com.vigil.application.telemetry;

import java.util.List;
import java.util.Objects;

/**
 * Per-key deltas between two snapshots, or a warming state with a reason (first sample, non-monotonic time, stats
 * reset, eviction or counter regression) in which no delta is produced. {@code partial} flags incomplete visibility
 * or entry churn, mirroring the workspace calculator.
 */
public record PerKeyDeltaResult(String state, List<KeyDelta> deltas, boolean partial, String reason) {
    public PerKeyDeltaResult {
        if (!(state.equals("available") || state.equals("warming_up"))) {
            throw new IllegalArgumentException("Invalid state");
        }
        if (state.equals("available") != (deltas != null)) {
            throw new IllegalArgumentException("State/deltas mismatch");
        }
        if (state.equals("warming_up") && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Reason required");
        }
        deltas = deltas == null ? null : List.copyOf(deltas);
    }

    public boolean isWarming() {
        return state.equals("warming_up");
    }

    public static PerKeyDeltaResult warming(String reason) {
        return new PerKeyDeltaResult("warming_up", null, false, reason);
    }

    public static PerKeyDeltaResult available(List<KeyDelta> deltas, boolean partial) {
        return new PerKeyDeltaResult("available", Objects.requireNonNull(deltas), partial, null);
    }
}
