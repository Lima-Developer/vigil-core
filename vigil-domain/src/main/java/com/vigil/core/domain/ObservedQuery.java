package com.vigil.core.domain;

import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ObservedQuery(
        QueryFingerprint fingerprint,
        TelemetrySource source,
        Instant observedAt,
        Optional<SourceQueryId> sourceQueryId,
        CommandType commandType,
        ObservationWindow observationWindow,
        ObservedQueryMetrics metrics
) {
    public ObservedQuery {
        Objects.requireNonNull(fingerprint, "Fingerprint must not be null");
        Objects.requireNonNull(source, "Source must not be null");
        Objects.requireNonNull(observedAt, "Observed at must not be null");
        Objects.requireNonNull(sourceQueryId, "Source query id must not be null");
        Objects.requireNonNull(commandType, "Command type must not be null");
        Objects.requireNonNull(observationWindow, "Observation window must not be null");
        Objects.requireNonNull(metrics, "Metrics must not be null");
    }
}
