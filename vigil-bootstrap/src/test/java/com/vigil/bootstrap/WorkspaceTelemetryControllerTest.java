package com.vigil.bootstrap;

import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.OptionalLong;
import java.util.ArrayList;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTelemetryControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    private WorkspaceTelemetryController controller(OptionalLong bytes) {
        return controller(bytes, new ArrayList<>());
    }

    private WorkspaceTelemetryController controller(OptionalLong bytes,
            ArrayList<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample> samples) {
        com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory history = new com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory() {
            public void append(com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample sample, Instant before, int maximum) { samples.add(sample); }
            public java.util.List<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample> recent(int limit) { return java.util.List.copyOf(samples); }
        };
        return new WorkspaceTelemetryController(
                new StorageConfiguration(Path.of("/tmp/local.db"), 1),
                new TargetProfile("lab", "test", "localhost", 5432, "postgres",
                        TargetSslMode.VERIFY_FULL, Duration.ofSeconds(5)),
                () -> NOW, ignored -> bytes, history,
                new StaticListableBeanFactory().getBeanProvider(ContinuousTelemetryCollector.class));
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

    @Test void exposesTheLatestPersistedDeltaWithoutInventingProviderStorage() {
        var samples = new ArrayList<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample>();
        samples.add(new com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample(
                NOW, NOW.minusSeconds(30), "lab", "available", "measured", 12L,
                new BigDecimal("60.0"), new BigDecimal("5.0"), 40L, 50L, 6L, 0L, 0L,
                3L, 2L, 1L, null));

        var snapshot = controller(OptionalLong.of(100), samples).show().getBody();

        assertNotNull(snapshot);
        assertAll(
                () -> assertEquals(12L, snapshot.metrics().get("sqlCalls").value()),
                () -> assertEquals(new BigDecimal("5.0"), snapshot.metrics().get("meanExecutionMillis").value()),
                () -> assertEquals(2L, snapshot.metrics().get("waitingSessions").value()),
                () -> assertEquals(new WorkspaceTelemetryController.Window(NOW.minusSeconds(30), NOW),
                        snapshot.metrics().get("sqlCalls").window()),
                () -> assertEquals("unsupported", snapshot.metrics().get("freeLocalStorage").state()),
                () -> assertEquals(samples, snapshot.samples())
        );
    }
}
