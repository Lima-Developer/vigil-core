package com.vigil.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.DriverManager;
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
    void acceptsPartialTargetOverrideWithProfileDefaults() {
        var variables = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "1", "SPRING_PROFILES_ACTIVE", "test",
                "VIGIL_TARGET_NAME", "target");
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(variables, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)));
        assertEquals("", errors.toString());
    }

    @Test
    void rejectsEachInvalidTargetOverrideBeforeStorage() {
        var invalidOverrides = Map.of(
                "VIGIL_TARGET_SSL_MODE", "disable",
                "VIGIL_TARGET_PORT", "invalid",
                "VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", "0");
        invalidOverrides.forEach((key, value) -> {
            var database = directory.resolve(key + ".db");
            var variables = new java.util.HashMap<String, String>();
            variables.put("VIGIL_DATABASE_PATH", database.toString());
            variables.put(key, value);
            var output = new ByteArrayOutputStream();
            var errors = new ByteArrayOutputStream();

            assertEquals(2, Main.run(Map.copyOf(variables), new PrintStream(output), new PrintStream(errors)), key);
            assertEquals("", output.toString(), key);
            assertTrue(errors.toString().contains(key), errors.toString());
            assertFalse(java.nio.file.Files.exists(database), key);
        });
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

    @Test
    void doesNotReportHealthyBeforeAnIncompatibleMigrationFails() throws Exception {
        var database = directory.resolve("vigil.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO schema_version (version) VALUES (1)");
        }
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var variables = Map.of("VIGIL_DATABASE_PATH", database.toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "1", "SPRING_PROFILES_ACTIVE", "test");

        assertEquals(1, Main.run(variables, new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("Falha ao verificar armazenamento"));
        assertFalse(errors.toString().contains("CREATE TABLE"));
    }
}
