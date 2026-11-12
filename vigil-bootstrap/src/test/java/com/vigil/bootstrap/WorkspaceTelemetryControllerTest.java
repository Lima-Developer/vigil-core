package com.vigil.bootstrap;

import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTelemetryControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    private WorkspaceTelemetryController controller(OptionalLong bytes) {
        return new WorkspaceTelemetryController(
                new StorageConfiguration(Path.of("/tmp/local.db"), 1),
                new TargetProfile("lab", "test", "localhost", 5432, "postgres",
                        TargetSslMode.VERIFY_FULL, Duration.ofSeconds(5)),
                () -> NOW, ignored -> bytes);
    }

    @Test void zeroFreeSpaceIsAvailableAndTargetMetricsRemainUnknown() {
        var response = controller(OptionalLong.of(0)).show();
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var snapshot = response.getBody();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.schemaVersion());
        assertEquals(NOW, snapshot.generatedAt());
        var local = snapshot.metrics().get("localUsableBytes");
        assertEquals("available", local.state());
        assertEquals(0L, local.value());
        assertEquals(NOW, local.observedAt());
        assertEquals("vigil-local-storage", local.targetId());
        for (var key : new String[]{"sqlCalls", "meanExecutionMillis", "waitingSessions", "freeLocalStorage"}) {
            var metric = snapshot.metrics().get(key);
            assertEquals("unsupported", metric.state());
            assertNull(metric.value());
            assertNull(metric.observedAt());
            assertNull(metric.window());
            assertEquals("lab", metric.targetId());
        }
    }

    @Test void failedReadDoesNotLeakAPathOrInventAValue() {
        var metric = controller(OptionalLong.empty()).show().getBody().metrics().get("localUsableBytes");
        assertEquals("error", metric.state());
        assertNull(metric.value());
        assertNull(metric.observedAt());
        assertEquals("LOCAL_VOLUME_UNREADABLE", metric.reason());
    }
}
