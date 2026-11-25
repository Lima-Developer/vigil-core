package com.vigil.core;

import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.NormalizedQueryTemplate;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.SourceQueryId;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ObservedQueryTest {

    private static final String VALID_FINGERPRINT =
            "hmac-sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void shouldExposeOnlySafeObservedQueryComponents() {
        var componentNames = Arrays.stream(ObservedQuery.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertEquals(
                List.of(
                        "fingerprint",
                        "source",
                        "observedAt",
                        "sourceQueryId",
                        "commandType",
                        "observationWindow",
                        "metrics"
                ),
                componentNames
        );
    }

    @Test
    void shouldCreateObservedQueryWithSafeAggregatedTelemetry() {
        // Given
        var fingerprint = new QueryFingerprint(
                VALID_FINGERPRINT,
                "v1"
        );

        var source = TelemetrySource.PG_STAT_STATEMENTS;
        var observedAt = Instant.parse("2026-09-07T12:05:00Z");
        var sourceQueryId = Optional.of(new SourceQueryId("16384"));
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
                observedAt,
                sourceQueryId,
                commandType,
                window,
                metrics
        );

        // Then
        assertAll(
                () -> assertEquals(fingerprint, observedQuery.fingerprint()),
                () -> assertEquals(source, observedQuery.source()),
                () -> assertEquals(observedAt, observedQuery.observedAt()),
                () -> assertEquals(sourceQueryId, observedQuery.sourceQueryId()),
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
                () -> new QueryFingerprint(VALID_FINGERPRINT, "")
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
                        Instant.parse("2026-09-07T12:05:00Z"),
                        Optional.empty(),
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

    @Test
    void shouldGroupEquivalentObservationsByVigilFingerprint() {
        var fingerprint = new QueryFingerprint(VALID_FINGERPRINT, "v1");
        var firstObservation = observedQuery(
                fingerprint,
                Instant.parse("2026-09-07T12:05:00Z"),
                Optional.of(new SourceQueryId("16384"))
        );
        var secondObservation = observedQuery(
                fingerprint,
                Instant.parse("2026-09-07T12:10:00Z"),
                Optional.of(new SourceQueryId("16385"))
        );

        assertAll(
                () -> assertEquals(firstObservation.fingerprint(), secondObservation.fingerprint()),
                () -> assertNotEquals(firstObservation.sourceQueryId(), secondObservation.sourceQueryId())
        );
    }

    @Test
    void shouldAllowObservationWithoutSourceQueryId() {
        var observedQuery = observedQuery(
                new QueryFingerprint(VALID_FINGERPRINT, "v1"),
                Instant.parse("2026-09-07T12:05:00Z"),
                Optional.empty()
        );

        assertTrue(observedQuery.sourceQueryId().isEmpty());
    }

    @Test
    void shouldKeepNormalizedTemplateDistinctAndRedacted() {
        var template = new NormalizedQueryTemplate("SELECT * FROM customer WHERE id = $1");

        assertAll(
                () -> assertNotEquals(QueryFingerprint.class, NormalizedQueryTemplate.class),
                () -> assertEquals("NormalizedQueryTemplate[redacted]", template.toString())
        );
    }

    @Test
    void shouldRejectBlankSourceQueryId() {
        assertThrows(IllegalArgumentException.class, () -> new SourceQueryId(" "));
    }

    @Test
    void shouldAcceptFingerprintWithAlgorithmPrefixAndSixtyFourHex() {
        var fingerprint = new QueryFingerprint(VALID_FINGERPRINT, "v1");

        assertEquals(VALID_FINGERPRINT, fingerprint.value());
    }

    @Test
    void shouldRejectFingerprintWithoutAlgorithmPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new QueryFingerprint(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "v1"));
    }

    @Test
    void shouldRejectFingerprintWhenHexIsNotSixtyFourCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryFingerprint("hmac-sha256:abcdef", "v1"));
    }

    @Test
    void shouldRejectFingerprintWhenHexHasUppercaseOrNonHexCharacters() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new QueryFingerprint(
                        "hmac-sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "v1")),
                () -> assertThrows(IllegalArgumentException.class, () -> new QueryFingerprint(
                        "hmac-sha256:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "v1")));
    }

    private ObservedQuery observedQuery(
            QueryFingerprint fingerprint,
            Instant observedAt,
            Optional<SourceQueryId> sourceQueryId
    ) {
        return new ObservedQuery(
                fingerprint,
                TelemetrySource.PG_STAT_STATEMENTS,
                observedAt,
                sourceQueryId,
                CommandType.SELECT,
                new ObservationWindow(
                        observedAt.minusSeconds(300),
                        observedAt
                ),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 5)
        );
    }
}
