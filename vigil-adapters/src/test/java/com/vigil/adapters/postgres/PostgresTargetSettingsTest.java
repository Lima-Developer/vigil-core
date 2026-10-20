package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresTargetSettingsTest {

    @Test
    void shouldApplyTheDefaultTimeouts() {
        var settings = PostgresTestSupport.settings();

        assertEquals(Duration.ofSeconds(10), settings.socketTimeout());
        assertEquals(Duration.ofSeconds(5), settings.statementTimeout());
        assertEquals(Duration.ofMillis(200), settings.lockTimeout());
        assertEquals("vigil_collector", settings.user());
    }

    @Test
    void shouldBuildTheJdbcUrlFromTheProfile() {
        assertEquals("jdbc:postgresql://localhost:5432/vigil_lab", PostgresTestSupport.settings().jdbcUrl());
    }

    @Test
    void shouldRenderSessionOptionsInMilliseconds() {
        assertEquals("-c statement_timeout=5000 -c lock_timeout=200 -c default_transaction_read_only=on",
                PostgresTestSupport.settings().sessionOptions());
    }

    @Test
    void shouldExposeTheRootCertificateOnlyWhenConfigured() {
        var path = Path.of("ca.crt");
        var withCert = PostgresTargetSettings.withDefaults(PostgresTestSupport.profile(), "u", path);

        assertEquals(path, withCert.optionalSslRootCert().orElseThrow());
        assertFalse(PostgresTestSupport.settings().optionalSslRootCert().isPresent());
    }

    @ParameterizedTest
    @CsvSource({
            "5000, 5s", "200, 200ms", "1, 1ms", "999, 999ms", "1000, 1s", "1500, 1500ms", "60000, 1min",
            "90000, 90s", "3600000, 1h", "5400000, 90min", "86400000, 1d", "172800000, 2d", "86400001, 86400001ms"
    })
    void shouldFormatTimeoutsLikeTheServerDoes(long millis, String expected) {
        assertEquals(expected, PostgresTargetSettings.serverFormat(millis));
    }

    @Test
    void shouldExposeTheExpectedServerValuesOfTheDefaults() {
        var settings = PostgresTestSupport.settings();

        assertEquals("5s", settings.expectedStatementTimeout());
        assertEquals("200ms", settings.expectedLockTimeout());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 10", "10, 11", "5, 5", "1, 1"
    })
    void shouldRejectASocketTimeoutThatIsNotGreaterThanTheStatementTimeout(long socket, long statement) {
        var profile = PostgresTestSupport.profile();

        assertThrows(IllegalArgumentException.class, () -> new PostgresTargetSettings(
                profile, "u", null, Duration.ofSeconds(socket), Duration.ofSeconds(statement), Duration.ofMillis(200)));
    }

    @Test
    void shouldAcceptASocketTimeoutOneMillisecondAboveTheStatementTimeout() {
        var settings = new PostgresTargetSettings(PostgresTestSupport.profile(), "u", null,
                Duration.ofMillis(5001), Duration.ofSeconds(5), Duration.ofMillis(200));

        assertEquals(Duration.ofMillis(5001), settings.socketTimeout());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectNonPositiveTimeouts(long millis) {
        var profile = PostgresTestSupport.profile();
        var bad = Duration.ofMillis(millis);
        var ok = Duration.ofSeconds(1);

        assertThrows(IllegalArgumentException.class,
                () -> new PostgresTargetSettings(profile, "u", null, Duration.ofSeconds(10), bad, ok));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresTargetSettings(profile, "u", null, Duration.ofSeconds(10), ok, bad));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresTargetSettings(profile, "u", null, bad, ok, ok));
    }

    @Test
    void shouldRejectTimeoutsBelowOneMillisecond() {
        var profile = PostgresTestSupport.profile();
        var tiny = Duration.ofNanos(500_000);

        assertThrows(IllegalArgumentException.class, () -> new PostgresTargetSettings(
                profile, "u", null, Duration.ofSeconds(10), Duration.ofSeconds(5), tiny));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void shouldRejectABlankUser(String user) {
        var profile = PostgresTestSupport.profile();

        assertThrows(IllegalArgumentException.class, () -> PostgresTargetSettings.withDefaults(profile, user, null));
    }

    @Test
    void shouldRejectNullComponents() {
        var profile = PostgresTestSupport.profile();
        var ten = Duration.ofSeconds(10);
        var five = Duration.ofSeconds(5);
        var lock = Duration.ofMillis(200);

        assertThrows(NullPointerException.class, () -> new PostgresTargetSettings(null, "u", null, ten, five, lock));
        assertThrows(NullPointerException.class, () -> new PostgresTargetSettings(profile, null, null, ten, five, lock));
        assertThrows(NullPointerException.class, () -> new PostgresTargetSettings(profile, "u", null, null, five, lock));
        assertThrows(NullPointerException.class, () -> new PostgresTargetSettings(profile, "u", null, ten, null, lock));
        assertThrows(NullPointerException.class, () -> new PostgresTargetSettings(profile, "u", null, ten, five, null));
    }

    @Test
    void shouldNotRevealSecretsOrThePasswordInToString() {
        var path = Path.of("/run/secrets/" + PostgresTestSupport.canary());
        var settings = PostgresTargetSettings.withDefaults(PostgresTestSupport.profile(), "vigil_collector", path);

        var text = settings.toString();

        assertFalse(text.contains(path.toString()));
        assertFalse(text.contains("jdbc:"));
        assertFalse(text.toLowerCase().contains("password"));
        assertTrue(text.contains("vigil_collector"));
        assertTrue(text.contains("statementTimeout=PT5S"));
    }
}
