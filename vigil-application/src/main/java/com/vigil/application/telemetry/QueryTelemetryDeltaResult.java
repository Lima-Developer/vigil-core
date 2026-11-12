package com.vigil.application.telemetry;

public record QueryTelemetryDeltaResult(String state, AggregatedTelemetryDelta delta, String reason) {
    public QueryTelemetryDeltaResult {
        if (!(state.equals("available") || state.equals("warming_up"))) throw new IllegalArgumentException("Invalid state");
        if (state.equals("available") != (delta != null)) throw new IllegalArgumentException("State/delta mismatch");
        if (state.equals("warming_up") && (reason == null || reason.isBlank())) throw new IllegalArgumentException("Reason required");
    }
    public static QueryTelemetryDeltaResult warming(String reason) { return new QueryTelemetryDeltaResult("warming_up", null, reason); }
    public static QueryTelemetryDeltaResult available(AggregatedTelemetryDelta delta) { return new QueryTelemetryDeltaResult("available", delta, null); }
}
