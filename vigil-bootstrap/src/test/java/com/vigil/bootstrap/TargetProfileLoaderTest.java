package com.vigil.bootstrap;

import com.vigil.application.configuration.TargetSslMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TargetProfileLoaderTest {

    private static Map<String, String> base() {
        var env = new HashMap<String, String>();
        env.put("VIGIL_TARGET_NAME", "alvo");
        env.put("VIGIL_ENVIRONMENT", "test");
        env.put("VIGIL_TARGET_HOST", "db.example.com");
        env.put("VIGIL_TARGET_DATABASE", "vigil_lab");
        return env;
    }

    private static Map<String, String> with(String key, String value) {
        var env = base();
        env.put(key, value);
        return Map.copyOf(env);
    }

    private static String failure(Map<String, String> env) {
        return assertThrows(IllegalArgumentException.class, () -> TargetProfileLoader.load(env)).getMessage();
    }

    @Test
    void appliesDefaults() {
        var profile = TargetProfileLoader.load(Map.copyOf(base()));
        assertAll(
                () -> assertEquals("alvo", profile.name()),
                () -> assertEquals("test", profile.environment()),
                () -> assertEquals("db.example.com", profile.host()),
                () -> assertEquals("vigil_lab", profile.database()),
                () -> assertEquals(5432, profile.port()),
                () -> assertEquals(TargetSslMode.VERIFY_FULL, profile.sslMode()),
                () -> assertEquals(Duration.ofSeconds(5), profile.connectTimeout())
        );
    }

    @Test
    void readsExplicitValues() {
        var env = base();
        env.put("VIGIL_TARGET_PORT", "6543");
        env.put("VIGIL_TARGET_SSL_MODE", "verify-ca");
        env.put("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", "60");
        var profile = TargetProfileLoader.load(Map.copyOf(env));
        assertAll(
                () -> assertEquals(6543, profile.port()),
                () -> assertEquals(TargetSslMode.VERIFY_CA, profile.sslMode()),
                () -> assertEquals(Duration.ofSeconds(60), profile.connectTimeout())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIGIL_TARGET_NAME", "VIGIL_ENVIRONMENT", "VIGIL_TARGET_HOST", "VIGIL_TARGET_DATABASE"})
    void namesMissingAndBlankRequiredVariables(String variable) {
        var missing = base();
        missing.remove(variable);
        assertAll(
                () -> assertTrue(failure(Map.copyOf(missing)).contains(variable)),
                () -> assertTrue(failure(with(variable, "")).contains(variable)),
                () -> assertTrue(failure(with(variable, "  ")).contains(variable))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIGIL_TARGET_PORT", "VIGIL_TARGET_SSL_MODE", "VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS"})
    void blankOptionalVariableIsInvalidInsteadOfDefault(String variable) {
        assertAll(
                () -> assertTrue(failure(with(variable, "")).contains(variable)),
                () -> assertTrue(failure(with(variable, " ")).contains(variable))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "0", "65536", "54.32", "5432.0", "+5432", " 5432", "99999999999999999999"})
    void rejectsInvalidPort(String port) {
        assertTrue(failure(with("VIGIL_TARGET_PORT", port)).contains("VIGIL_TARGET_PORT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "require", "VERIFY-FULL", "verify-full "})
    void rejectsInsecureSslMode(String mode) {
        assertTrue(failure(with("VIGIL_TARGET_SSL_MODE", mode)).contains("VIGIL_TARGET_SSL_MODE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "61", "-1", "2.5", "PT5S", "abc", "5s"})
    void rejectsInvalidTimeout(String timeout) {
        assertTrue(failure(with("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", timeout))
                .contains("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS"));
    }

    @Test
    void acceptsTimeoutBoundaries() {
        assertAll(
                () -> assertEquals(Duration.ofSeconds(1),
                        TargetProfileLoader.load(with("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", "1")).connectTimeout()),
                () -> assertEquals(65535, TargetProfileLoader.load(with("VIGIL_TARGET_PORT", "65535")).port()),
                () -> assertEquals(1, TargetProfileLoader.load(with("VIGIL_TARGET_PORT", "1")).port())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"host/db", "user@host", "host?x", "jdbc://h", "::1"})
    void namesInvalidHost(String host) {
        assertTrue(failure(with("VIGIL_TARGET_HOST", host)).contains("VIGIL_TARGET_HOST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a;b", "a?b", "a&b", "a=b", "a/b", "a b", ""})
    void namesInvalidDatabase(String database) {
        assertTrue(failure(with("VIGIL_TARGET_DATABASE", database)).contains("VIGIL_TARGET_DATABASE"));
    }

    @Test
    void namesDatabaseLongerThan63() {
        assertTrue(failure(with("VIGIL_TARGET_DATABASE", "d".repeat(64))).contains("VIGIL_TARGET_DATABASE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIGIL_TARGETS", "VIGIL_TARGET_2_HOST", "VIGIL_TARGET_10_NAME", "VIGIL_TARGET_0_"})
    void rejectsMultipleTargets(String key) {
        assertTrue(failure(with(key, "x")).contains(key));
    }

    @Test
    void reportsFirstMultipleTargetKeyInSortedOrderBeforeOtherErrors() {
        var env = new HashMap<String, String>();
        env.put("VIGIL_TARGET_3_HOST", "x");
        env.put("VIGIL_TARGET_2_HOST", "y");
        assertTrue(failure(Map.copyOf(env)).contains("VIGIL_TARGET_2_HOST"));
    }

    @Test
    void targetNameAndHostAreNotIndexedTargetKeys() {
        var env = base();
        env.put("VIGIL_TARGET_NAME", "alvo");
        env.put("VIGIL_TARGET_HOST", "db.example.com");
        assertEquals("alvo", TargetProfileLoader.load(Map.copyOf(env)).name());
    }

    @Test
    void doesNotTreatSimilarKeysAsMultipleTargets() {
        var env = base();
        env.put("VIGIL_TARGET_PORT", "5432");
        env.put("VIGIL_TARGETX", "x");
        assertDoesNotThrow(() -> TargetProfileLoader.load(Map.copyOf(env)));
    }

    @Test
    void neverEchoesRawValues() {
        var secretLike = "hunter2/segredo";
        assertAll(
                () -> assertFalse(failure(with("VIGIL_TARGET_HOST", secretLike)).contains(secretLike)),
                () -> assertFalse(failure(with("VIGIL_TARGET_DATABASE", secretLike)).contains(secretLike)),
                () -> assertFalse(failure(with("VIGIL_TARGET_PORT", secretLike)).contains(secretLike)),
                () -> assertFalse(failure(with("VIGIL_TARGET_SSL_MODE", secretLike)).contains(secretLike)),
                () -> assertFalse(failure(with("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", secretLike)).contains(secretLike))
        );
    }
}
