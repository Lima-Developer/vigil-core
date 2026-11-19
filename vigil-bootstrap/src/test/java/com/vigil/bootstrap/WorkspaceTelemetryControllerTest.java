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
                new StorageConfiguration(Path.of(System.getProperty("java.io.tmpdir"), "vigil-workspace", "local.db")
                        .toAbsolutePath(), 1),
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

    @Test void exposesWarmupAndErrorAsUnavailableInsteadOfSyntheticMetrics() {
        var warming = new ArrayList<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample>();
        warming.add(new com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample(
                NOW, null, "lab", "warming_up", "measured", null, null, null, null, null, null, null,
                null, 2L, 1L, 0L, "FIRST_SAMPLE"));
        var warmMetrics = controller(OptionalLong.of(1), warming).show().getBody().metrics();
        assertEquals("warming_up", warmMetrics.get("sqlCalls").state());
        assertEquals("FIRST_SAMPLE", warmMetrics.get("sqlCalls").reason());
        assertEquals("available", warmMetrics.get("waitingSessions").state());
        assertEquals(1L, warmMetrics.get("waitingSessions").value());

        var failure = new ArrayList<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample>();
        failure.add(new com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample(
                NOW, null, "lab", "error", "unknown", null, null, null, null, null, null, null,
                null, null, null, null, "COLLECTION_FAILED"));
        var errorMetrics = controller(OptionalLong.of(1), failure).show().getBody().metrics();
        assertEquals("error", errorMetrics.get("sqlCalls").state());
        assertEquals("error", errorMetrics.get("waitingSessions").state());
        assertEquals("COLLECTION_FAILED", errorMetrics.get("waitingSessions").reason());
    }

    @Test void preservesPartialReasonForAvailableTelemetry() {
        var samples = new ArrayList<com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample>();
        samples.add(new com.vigil.application.port.out.interfaces.WorkspaceTelemetrySample(
                NOW, NOW.minusSeconds(30), "lab", "available", "partial", 1L,
                BigDecimal.ONE, BigDecimal.ONE, 1L, 1L, 1L, 0L, 0L, 1L, 0L, 0L,
                "PARTIAL_ACTIVITY_VISIBILITY"));
        var metrics = controller(OptionalLong.of(1), samples).show().getBody().metrics();
        assertEquals("partial", metrics.get("sqlCalls").quality());
        assertEquals("PARTIAL_ACTIVITY_VISIBILITY", metrics.get("waitingSessions").reason());
    }
}
