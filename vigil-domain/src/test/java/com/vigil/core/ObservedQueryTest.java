package com.vigil.core;

import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ObservedQueryTest {

    @Test
    void shouldExposeOnlySafeObservedQueryComponents() {
        var componentNames = Arrays.stream(ObservedQuery.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertEquals(
                List.of("fingerprint", "source", "commandType", "observationWindow", "metrics"),
                componentNames
        );
    }

    @Test
    void shouldCreateObservedQueryWithSafeAggregatedTelemetry() {
        // Given
        var fingerprint = new QueryFingerprint(
                "pgss:8f15d2",
                "v1"
        );

        var source = TelemetrySource.PG_STAT_STATEMENTS;
        var commandType = CommandType.SELECT;

        var window = new ObservationWindow(
                Instant.parse("2026-09-07T12:00:00Z"),
                Instant.parse("2026-09-07T12:05:00Z")
        );

        var metrics = new ObservedQueryMetrics(
                120,
                Duration.ofSeconds(60),
                800
        );

        // When
        var observedQuery = new ObservedQuery(
                fingerprint,
                source,
                commandType,
                window,
                metrics
        );

        // Then
        assertAll(
                () -> assertEquals(fingerprint, observedQuery.fingerprint()),
                () -> assertEquals(source, observedQuery.source()),
                () -> assertEquals(commandType, observedQuery.commandType()),
                () -> assertEquals(window, observedQuery.observationWindow()),
                () -> assertEquals(metrics, observedQuery.metrics())
        );
    }

    @Test
    void shouldRejectFingerprintWhenValueIsBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QueryFingerprint("   ", "v1")
        );
    }

    @Test
    void shouldRejectFingerprintWhenAlgorithmVersionIsBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QueryFingerprint("pgss:8f15d2", "")
        );
    }

    @Test
    void shouldRejectObservationWindowWhenStartIsAfterEnd() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObservationWindow(
                        Instant.parse("2026-09-07T12:05:00Z"),
                        Instant.parse("2026-09-07T12:00:00Z")
                )
        );
    }

    @Test
    void shouldRejectMetricsWhenCallsAreNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObservedQueryMetrics(
                        -120,
                        Duration.ofSeconds(2),
                        800
                )
        );
    }

    @Test
    void shouldRejectObservedQueryWhenFingerprintIsNull() {
        var window = new ObservationWindow(
                Instant.parse("2026-09-07T12:00:00Z"),
                Instant.parse("2026-09-07T12:05:00Z")
        );

        var metrics = new ObservedQueryMetrics(
                120,
                Duration.ofSeconds(2),
                800
        );

        assertThrows(
                NullPointerException.class,
                () -> new ObservedQuery(
                        null,
                        TelemetrySource.PG_STAT_STATEMENTS,
                        CommandType.SELECT,
                        window,
                        metrics
                )
        );
    }

    @Test
    void shouldRejectObservationWindowWhenStartEqualsEnd() {
        var instant = Instant.parse("2026-09-07T12:00:00Z");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ObservationWindow(instant, instant)
        );
    }

    @Test
    void shouldRejectMetricsWhenRowsAreNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObservedQueryMetrics(
                        120,
                        Duration.ofSeconds(2),
                        -1
                )
        );
    }

    @Test
    void shouldRejectMetricsWhenTotalExecutionTimeIsNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObservedQueryMetrics(
                        120,
                        Duration.ofSeconds(-1),
                        800
                )
        );
    }
}
