package com.vigil.application.port.out.interfaces;

import java.time.Instant;
import java.util.List;

public interface WorkspaceTelemetryHistory {
    void append(WorkspaceTelemetrySample sample, Instant deleteBefore, int maximumSamples);
    List<WorkspaceTelemetrySample> recent(int limit);
}
