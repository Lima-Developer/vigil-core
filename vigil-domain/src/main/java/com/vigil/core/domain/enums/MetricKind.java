package com.vigil.core.domain.enums;

public enum MetricKind {
    CALLS(MeasurementUnit.EXECUTIONS),
    TOTAL_EXECUTION_TIME(MeasurementUnit.MILLISECONDS),
    ROWS(MeasurementUnit.ROWS),
    SHARED_BLOCKS_READ(MeasurementUnit.BLOCKS);

    private final MeasurementUnit unit;

    MetricKind(MeasurementUnit unit) {
        this.unit = unit;
    }

    public MeasurementUnit unit() {
        return unit;
    }
}
