package com.vigil.application.target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TargetCapabilitiesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsCheckResult() {
        var capabilities = new TargetCapabilities("16.2", true, NOW);
        assertAll(
                () -> assertEquals("16.2", capabilities.serverVersion()),
                () -> assertTrue(capabilities.pgStatStatementsInstalled()),
                () -> assertEquals(NOW, capabilities.checkedAt())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankVersion(String version) {
        assertThrows(IllegalArgumentException.class, () -> new TargetCapabilities(version, false, NOW));
    }

    @Test
    void rejectsNulls() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new TargetCapabilities(null, false, NOW)),
                () -> assertThrows(NullPointerException.class, () -> new TargetCapabilities("16", false, null))
        );
    }
}
