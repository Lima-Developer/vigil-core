package com.vigil.application.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TargetSslModeTest {

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "require", "VERIFY-FULL", "Verify-Ca", " verify-full",
            "verify-full ", "verify_full", ""})
    @NullSource
    void rejectsInsecureOrUnknownSslMode(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> TargetSslMode.fromConfigValue(value));
        assertTrue(error.getMessage().contains("VIGIL_TARGET_SSL_MODE"));
    }

    @Test
    void parsesOnlySecureSslModes() {
        assertAll(
                () -> assertEquals(TargetSslMode.VERIFY_CA, TargetSslMode.fromConfigValue("verify-ca")),
                () -> assertEquals(TargetSslMode.VERIFY_FULL, TargetSslMode.fromConfigValue("verify-full")),
                () -> assertEquals("verify-ca", TargetSslMode.VERIFY_CA.configValue()),
                () -> assertEquals("verify-full", TargetSslMode.VERIFY_FULL.configValue()),
                () -> assertEquals(2, TargetSslMode.values().length)
        );
    }
}
