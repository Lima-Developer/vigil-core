package com.vigil.adapters.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStorageProbeTest {
    @TempDir Path directory;

    @Test
    void probesSpaceAndDeletesItsTemporaryFile() throws Exception {
        var file = directory.resolve("vigil.db");
        var facts = new SqliteStorageProbe(path -> 256L).inspect(file);
        assertTrue(facts.directoryExists());
        assertTrue(facts.directoryWritable());
        assertTrue(facts.fileWritable());
        assertEquals(256L, facts.usableBytes());
        assertNull(facts.integrityResult());
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void reportsMissingDirectory() {
        var facts = new SqliteStorageProbe().inspect(directory.resolve("missing/vigil.db"));
        assertFalse(facts.directoryExists());
        assertTrue(facts.failure().contains("missing"));
    }

    @Test
    void checksExistingDatabaseAndReportsOpenFailure() throws Exception {
        var file = directory.resolve("vigil.db");
        try (var ignored = new SqliteConnectionFactory(file).open()) {
            assertEquals("ok", new SqliteStorageProbe().inspect(file).integrityResult());
        }
        Files.writeString(file, "not sqlite");
        assertNotNull(new SqliteStorageProbe().inspect(file).failure());
    }

    @Test
    void reportsReadOnlyDatabaseWithActionablePath() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        var file = directory.resolve("vigil.db");
        try (var ignored = new SqliteConnectionFactory(file).open()) {
            // Create a real database before changing permissions.
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
            var facts = new SqliteStorageProbe().inspect(file);
            assertFalse(facts.fileWritable());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void reportsReadOnlyDirectory() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            assertFalse(new SqliteStorageProbe().inspect(directory.resolve("vigil.db")).directoryWritable());
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void reportsCorruptDatabaseAsUnhealthyEvidence() throws Exception {
        var file = directory.resolve("vigil.db");
        try (var connection = new SqliteConnectionFactory(file).open()) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
        Files.write(file, new byte[]{0, 1, 2, 3});
        var facts = new SqliteStorageProbe().inspect(file);
        assertTrue(facts.failure() != null || (facts.integrityResult() != null
                && !"ok".equals(facts.integrityResult())));
    }
}
