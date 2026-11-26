package com.vigil.application.port.out.interfaces;

import java.time.Instant;

/**
 * Collects a snapshot for the impact selection path, where each entry carries its query-family fingerprint computed
 * inside the adapter. This is deliberately separate from {@link QueryTelemetryPort}: the continuous collection cycle
 * (whose overhead is measured and bounded) must not read the query text or compute fingerprints.
 */
public interface ImpactTelemetryPort {
    ImpactTelemetrySnapshot collectForImpactSelection(Instant at);
}
