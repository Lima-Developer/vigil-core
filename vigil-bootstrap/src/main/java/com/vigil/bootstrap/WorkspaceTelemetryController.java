package com.vigil.bootstrap;

import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.LocalVolumeSpaceReader;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Versioned observations: unavailable database metrics never masquerade as measured zeros. */
@RestController
@RequestMapping("/api/workspace/telemetry")
class WorkspaceTelemetryController {
    private final StorageConfiguration storage;
    private final TargetProfile target;
    private final Clock clock;
    private final LocalVolumeSpaceReader volume;
    private final WorkspaceTelemetryHistory history;
    private final ObjectProvider<ContinuousTelemetryCollector> collector;

    WorkspaceTelemetryController(StorageConfiguration storage, TargetProfile target,
                                 Clock clock, LocalVolumeSpaceReader volume, WorkspaceTelemetryHistory history,
                                 ObjectProvider<ContinuousTelemetryCollector> collector) {
        this.storage = storage;
        this.target = target;
        this.clock = clock;
        this.volume = volume;
        this.history = history;
        this.collector = collector;
    }

    @GetMapping
    ResponseEntity<Snapshot> show() {
        Instant now = clock.now();
        List<WorkspaceTelemetrySample> samples = history.recent(120);
        WorkspaceTelemetrySample latest = samples.isEmpty() ? null : samples.getFirst();
        var metrics = new LinkedHashMap<String, Metric>();
        metrics.put("sqlCalls", counterMetric(latest, "calls", latest == null ? null : latest.calls()));
        metrics.put("meanExecutionMillis", counterMetric(
                latest, "ms", latest == null ? null : latest.meanExecutionMillis()));
        metrics.put("waitingSessions", gaugeMetric(latest));
        metrics.put("freeLocalStorage", unavailable("provider", "bytes", "PROVIDER_NOT_CONFIGURED"));
        var bytes = volume.usableBytes(storage.databaseFile());
        metrics.put("localUsableBytes", new Metric("filesystem", "vigil-local-storage",
                bytes.isPresent() ? now : null, null, "bytes",
                bytes.isPresent() ? "available" : "error",
                bytes.isPresent() ? "measured" : "unknown",
                bytes.isPresent() ? bytes.getAsLong() : null,
                bytes.isPresent() ? null : "LOCAL_VOLUME_UNREADABLE"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Snapshot(1, now, Map.copyOf(metrics), samples, status()));
    }

    @PostMapping("/pause")
    ResponseEntity<CollectorStatus> pause() {
        var current = collector.getIfAvailable();
        if (current != null) current.pause();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status());
    }

    @PostMapping("/resume")
    ResponseEntity<CollectorStatus> resume() {
        var current = collector.getIfAvailable();
        if (current != null) current.resume();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status());
    }

    private Metric counterMetric(WorkspaceTelemetrySample sample, String unit, Number value) {
        if (sample == null) return unavailable("pg_stat_statements", unit, "COLLECTOR_NOT_CONFIGURED");
        if (!"available".equals(sample.state()) || value == null) {
            return unavailable("pg_stat_statements", unit, metricState(sample), metricReason(sample, value));
        }
        return new Metric("pg_stat_statements", sample.targetId(), sample.observedAt(),
                new Window(sample.windowStart(), sample.observedAt()), unit, "available", sample.quality(), value,
                sample.reason());
    }

    private Metric gaugeMetric(WorkspaceTelemetrySample sample) {
        if (sample == null) return unavailable("pg_stat_activity", "sessions", "COLLECTOR_NOT_CONFIGURED");
        if (sample.waitingSessions() == null || "error".equals(sample.state())) {
            return unavailable("pg_stat_activity", "sessions", metricState(sample), metricReason(sample, null));
        }
        String quality = "partial".equals(sample.quality()) ? "partial" : "measured";
        String reason = "partial".equals(quality) ? sample.reason() : null;
        return new Metric("pg_stat_activity", sample.targetId(), sample.observedAt(), null, "sessions",
                "available", quality, sample.waitingSessions(), reason);
    }

    private static String metricState(WorkspaceTelemetrySample sample) {
        return "error".equals(sample.state()) ? "error" : "warming_up";
    }

    private static String metricReason(WorkspaceTelemetrySample sample, Number value) {
        if (value == null && "available".equals(sample.state())) return "NO_COMPLETED_CALLS";
        return sample.reason() == null ? "COLLECTION_NOT_READY" : sample.reason();
    }

    private CollectorStatus status() {
        var current = collector.getIfAvailable();
        if (current == null) return new CollectorStatus(false, false, false, null, null, 0, "DISABLED");
        var s = current.status();
        return new CollectorStatus(s.running(), s.paused(), s.collecting(), s.lastSuccess(), s.lastFailure(),
                s.consecutiveFailures(), null);
    }

    private Metric unavailable(String source, String unit, String reason) {
        return unavailable(source, unit, "unsupported", reason);
    }

    private Metric unavailable(String source, String unit, String state, String reason) {
        return new Metric(source, target.name(), null, null, unit, state, "unknown", null, reason);
    }

    record Window(Instant start, Instant end) { }
    record Metric(String source, String targetId, Instant observedAt, Window window,
                  String unit, String state, String quality, Number value, String reason) { }
    record CollectorStatus(boolean enabled, boolean paused, boolean collecting, Instant lastSuccess,
                           String lastFailure, int consecutiveFailures, String reason) { }
    record Snapshot(int schemaVersion, Instant generatedAt, Map<String, Metric> metrics,
                    List<WorkspaceTelemetrySample> samples,
                    CollectorStatus collector) { }
}
