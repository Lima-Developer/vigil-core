package com.vigil.application.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TargetProfileTest {

    private static TargetProfile profile(String name, String environment, String host, int port, String database,
                                         TargetSslMode sslMode, Duration timeout) {
        return new TargetProfile(name, environment, host, port, database, sslMode, timeout);
    }

    private static TargetProfile valid() {
        return profile("alvo", "test", "db.example.com", 5432, "vigil_lab", TargetSslMode.VERIFY_FULL,
                Duration.ofSeconds(5));
    }

    @Test
    void acceptsValidProfile() {
        var profile = valid();
        assertEquals("alvo", profile.name());
        assertEquals(5432, TargetProfile.DEFAULT_PORT);
    }

    @Test
    void acceptsBoundaries() {
        assertDoesNotThrow(() -> profile("a", "e", "a".repeat(253), 1, "d".repeat(63), TargetSslMode.VERIFY_CA,
                Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> profile("a", "e", "h", 65535, "d", TargetSslMode.VERIFY_CA, Duration.ofSeconds(60)));
    }

    @Test
    void rejectsNullComponents() {
        var ok = valid();
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> profile(null, "e", "h", 1, "d", ok.sslMode(), ok.connectTimeout())),
                () -> assertThrows(NullPointerException.class, () -> profile("n", null, "h", 1, "d", ok.sslMode(), ok.connectTimeout())),
                () -> assertThrows(NullPointerException.class, () -> profile("n", "e", null, 1, "d", ok.sslMode(), ok.connectTimeout())),
                () -> assertThrows(NullPointerException.class, () -> profile("n", "e", "h", 1, null, ok.sslMode(), ok.connectTimeout())),
                () -> assertThrows(NullPointerException.class, () -> profile("n", "e", "h", 1, "d", null, ok.connectTimeout())),
                () -> assertThrows(NullPointerException.class, () -> profile("n", "e", "h", 1, "d", ok.sslMode(), null))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankNameAndEnvironment(String blank) {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(blank, "e", "h", 1, "d", TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1))).getMessage().contains("Nome")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile("n", blank, "h", 1, "d", TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1))).getMessage().contains("Ambiente"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "db host", "jdbc://host", "host/db", "user@host", "host?x=1", "host#a", "host;x",
            "::1", "[::1]", "host:5432", "hóst", "a\nb"})
    void rejectsInvalidHost(String host) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> profile("n", "e", host, 1, "d", TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("Host"));
    }

    @Test
    void rejectsHostLongerThan253() {
        assertThrows(IllegalArgumentException.class, () -> profile("n", "e", "a".repeat(254), 1, "d",
                TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE})
    void rejectsInvalidPort(int port) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> profile("n", "e", "h", port, "d", TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("Porta"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b", "a;b", "a?b", "a&b", "a=b", "a/b", "a.b", "a\nb"})
    void rejectsInvalidDatabase(String database) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> profile("n", "e", "h", 1, database, TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("Database"));
    }

    @Test
    void rejectsDatabaseLongerThan63() {
        assertThrows(IllegalArgumentException.class, () -> profile("n", "e", "h", 1, "d".repeat(64),
                TargetSslMode.VERIFY_FULL, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsInvalidTimeout() {
        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61),
                Duration.ofMillis(1500), Duration.ofMillis(500), Duration.ofSeconds(60).plusNanos(1),
                Duration.ofSeconds(1).minusNanos(1)}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> profile("n", "e", "h", 1, "d", TargetSslMode.VERIFY_FULL, timeout), timeout.toString());
            assertTrue(error.getMessage().contains("Timeout"));
        }
    }

    @Test
    void toStringShowsAllFieldsWithoutAmbiguity() {
        assertEquals("TargetProfile[name=alvo, environment=test, host=db.example.com, port=5432, "
                + "database=vigil_lab, sslMode=verify-full]", valid().toString());
    }

    @Test
    void hasNoSecretComponent() {
        var names = Arrays.stream(TargetProfile.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(java.util.List.of("name", "environment", "host", "port", "database", "sslMode", "connectTimeout"),
                names);
        for (String name : names) {
            assertFalse(name.toLowerCase().matches(".*(password|passwd|secret|token|credential).*"), name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "require", "VERIFY-FULL", "Verify-Ca", " verify-full", "verify-full ", ""})
    @NullSource
    void rejectsInsecureOrUnknownSslMode(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> TargetSslMode.fromConfigValue(value));
        assertTrue(error.getMessage().contains("VIGIL_TARGET_SSL_MODE"));
    }

    @Test
    void parsesSecureSslModes() {
        assertAll(
                () -> assertEquals(TargetSslMode.VERIFY_CA, TargetSslMode.fromConfigValue("verify-ca")),
                () -> assertEquals(TargetSslMode.VERIFY_FULL, TargetSslMode.fromConfigValue("verify-full")),
                () -> assertEquals("verify-ca", TargetSslMode.VERIFY_CA.configValue()),
                () -> assertEquals(2, TargetSslMode.values().length)
        );
    }
}
