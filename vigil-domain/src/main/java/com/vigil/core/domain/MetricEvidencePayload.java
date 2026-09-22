package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.math.BigDecimal;
import java.util.Objects;

public record MetricEvidencePayload(
        MetricKind metric,
        BigDecimal value,
        MeasurementUnit unit,
        ObservationWindow observationWindow
) implements EvidencePayload {
    public MetricEvidencePayload {
        Objects.requireNonNull(metric, "Metric must not be null.");
        Objects.requireNonNull(value, "Metric value must not be null.");
        Objects.requireNonNull(unit, "Measurement unit must not be null.");
        Objects.requireNonNull(observationWindow, "Observation window must not be null.");

        if (value.signum() < 0) {
            throw new IllegalArgumentException("Metric value must not be negative.");
        }

        if (metric.unit() != unit) {
            throw new IllegalArgumentException("Metric unit must be explicit and match its metric kind.");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.METRIC;
    }
}
