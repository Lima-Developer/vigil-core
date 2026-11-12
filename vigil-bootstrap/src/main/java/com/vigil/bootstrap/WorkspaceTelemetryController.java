package com.vigil.bootstrap;

import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.LocalVolumeSpaceReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    WorkspaceTelemetryController(StorageConfiguration storage, TargetProfile target,
                                 Clock clock, LocalVolumeSpaceReader volume) {
        this.storage = storage;
        this.target = target;
        this.clock = clock;
        this.volume = volume;
    }

    @GetMapping
    ResponseEntity<Snapshot> show() {
        Instant now = clock.now();
        var metrics = new LinkedHashMap<String, Metric>();
        metrics.put("sqlCalls", unavailable("pg_stat_statements", "calls", "COLLECTOR_NOT_CONFIGURED"));
        metrics.put("meanExecutionMillis", unavailable("pg_stat_statements", "ms", "COLLECTOR_NOT_CONFIGURED"));
        metrics.put("waitingSessions", unavailable("pg_stat_activity", "sessions", "COLLECTOR_NOT_CONFIGURED"));
        metrics.put("freeLocalStorage", unavailable("provider", "bytes", "PROVIDER_NOT_CONFIGURED"));
        var bytes = volume.usableBytes(storage.databaseFile());
        metrics.put("localUsableBytes", new Metric("filesystem", "vigil-local-storage",
                bytes.isPresent() ? now : null, null, "bytes",
                bytes.isPresent() ? "available" : "error",
                bytes.isPresent() ? "measured" : "unknown",
                bytes.isPresent() ? bytes.getAsLong() : null,
                bytes.isPresent() ? null : "LOCAL_VOLUME_UNREADABLE"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Snapshot(1, now, Map.copyOf(metrics)));
    }

    private Metric unavailable(String source, String unit, String reason) {
        return new Metric(source, target.name(), null, null, unit, "unsupported", "unknown", null, reason);
    }

    record Window(Instant start, Instant end) { }
    record Metric(String source, String targetId, Instant observedAt, Window window,
                  String unit, String state, String quality, Number value, String reason) { }
    record Snapshot(int schemaVersion, Instant generatedAt, Map<String, Metric> metrics) { }
}
