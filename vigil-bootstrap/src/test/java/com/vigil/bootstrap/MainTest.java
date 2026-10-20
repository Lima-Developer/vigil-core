package com.vigil.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path directory;

    @Test
    void requiresDatabasePath() {
        var error = assertThrows(IllegalArgumentException.class, () -> Main.storageConfiguration(Map.of()));
        assertTrue(error.getMessage().contains("VIGIL_DATABASE_PATH"));
    }

    @Test
    void usesDefaultMinimum() {
        var configuration = Main.storageConfiguration(Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString()));
        assertEquals(268_435_456L, configuration.minimumFreeBytes());
    }

    @Test
    void namesInvalidMinimum() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "invalid");
        var error = assertThrows(IllegalArgumentException.class, () -> Main.storageConfiguration(variables));
        assertTrue(error.getMessage().contains("VIGIL_STORAGE_MIN_FREE_BYTES"));
    }

    @Test
    void namesNonPositiveMinimum() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "0");
        var error = assertThrows(IllegalArgumentException.class, () -> Main.storageConfiguration(variables));
        assertTrue(error.getMessage().contains("VIGIL_STORAGE_MIN_FREE_BYTES"));
    }

    @Test
    void returnsConfigurationExitCode() {
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(Map.of(), new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)));
        assertTrue(errors.toString().contains("VIGIL_DATABASE_PATH"));
    }

    @Test
    void returnsUnhealthyExitCodeForMissingVolume() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("missing/vigil.db").toString(),
                "VIGIL_TARGET_NAME", "target", "VIGIL_ENVIRONMENT", "test",
                "VIGIL_TARGET_HOST", "localhost", "VIGIL_TARGET_DATABASE", "lab");
        var errors = new ByteArrayOutputStream();
        assertEquals(1, Main.run(variables, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)));
        assertTrue(errors.toString().contains("Diretório do banco inexistente"));
    }

    @Test
    void returnsHealthyExitCodeForWritableVolume() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "1", "VIGIL_TARGET_NAME", "target", "VIGIL_ENVIRONMENT", "test",
                "VIGIL_TARGET_HOST", "localhost", "VIGIL_TARGET_DATABASE", "lab");
        assertEquals(0, Main.run(variables, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void returnsConfigurationExitCodeForInvalidTargetBeforeAnyConnection() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_TARGET_NAME", "target", "VIGIL_ENVIRONMENT", "test",
                "VIGIL_TARGET_HOST", "localhost", "VIGIL_TARGET_DATABASE", "lab",
                "VIGIL_TARGET_SSL_MODE", "disable");
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(variables, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)));
        assertTrue(errors.toString().contains("VIGIL_TARGET_SSL_MODE"));
    }

    @Test
    void returnsConfigurationExitCodeForMultipleTargets() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_TARGET_NAME", "target", "VIGIL_ENVIRONMENT", "test",
                "VIGIL_TARGET_HOST", "localhost", "VIGIL_TARGET_DATABASE", "lab",
                "VIGIL_TARGET_2_HOST", "other");
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(variables, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)));
        assertTrue(errors.toString().contains("VIGIL_TARGET_2_HOST"));
    }
}
