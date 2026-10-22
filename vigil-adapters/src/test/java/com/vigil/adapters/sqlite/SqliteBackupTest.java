package com.vigil.adapters.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static com.vigil.adapters.sqlite.SqliteTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.*;

class SqliteBackupTest {
    @TempDir Path directory;

    @Test
    void backsUpCommittedSnapshotAndRestoresStandaloneFile() throws Exception {
        var source = new SqliteConnectionFactory(directory.resolve("source.db"));
        SqliteWalConcurrencyTest.migrate(source);
        var destination = directory.resolve("backup.db");
        List<String> before;
        try (var writer = source.open()) {
            SqliteWalConcurrencyTest.insertCase(writer, "11111111-1111-1111-1111-111111111111");
            execute(writer, """
                    INSERT INTO case_event (case_id, sequence, from_status, to_status, actor, reason, occurred_at)
                    VALUES ('11111111-1111-1111-1111-111111111111', 1, 'DISCOVERED', 'TRIAGED', 'analyst', 'triage',
                    '2026-09-30T12:00:00.000000000Z')""");
            before = snapshot(writer);
            execute(writer, "BEGIN IMMEDIATE");
            SqliteWalConcurrencyTest.insertCase(writer, "22222222-2222-2222-2222-222222222222");
            new SqliteBackup(source).backupTo(destination);
            execute(writer, "ROLLBACK");
            assertEquals(before, snapshot(writer));
        }
        assertTrue(Files.exists(destination));
        assertFalse(Files.exists(directory.resolve("backup.db-wal")));
        var restoredFile = directory.resolve("restored.db");
        Files.copy(destination, restoredFile);
        try (var restored = new SqliteConnectionFactory(restoredFile).open()) {
            assertEquals(List.of("ok"), rows(restored, "PRAGMA quick_check"));
            assertEquals(List.of("1"), rows(restored, "SELECT count(*) FROM analysis_case"));
            assertEquals(List.of("1"), rows(restored, "SELECT count(*) FROM case_event"));
        }
    }

    @Test
    void refusesExistingDestinationWithoutChangingIt() throws Exception {
        var source = new SqliteConnectionFactory(directory.resolve("source.db"));
        SqliteWalConcurrencyTest.migrate(source);
        var destination = directory.resolve("existing.db");
        Files.writeString(destination, "keep me");
        assertThrows(SqliteBackupException.class, () -> new SqliteBackup(source).backupTo(destination));
        assertEquals("keep me", Files.readString(destination));
    }

    @Test
    void acceptsSnapshotWhenWriterCommitsAfterVacuum() throws Exception {
        var source = new SqliteConnectionFactory(directory.resolve("source.db"));
        SqliteWalConcurrencyTest.migrate(source);
        var destination = directory.resolve("backup.db");
        try (var writer = source.open()) {
            SqliteWalConcurrencyTest.insertCase(writer, "11111111-1111-1111-1111-111111111111");
            new SqliteBackup(source, () -> {
                try {
                    SqliteWalConcurrencyTest.insertCase(writer, "22222222-2222-2222-2222-222222222222");
                } catch (java.sql.SQLException exception) {
                    throw new RuntimeException(exception);
                }
            }).backupTo(destination);
            assertEquals(List.of("2"), rows(writer, "SELECT count(*) FROM analysis_case"));
        }
        try (var backup = new SqliteConnectionFactory(destination).open()) {
            assertEquals(List.of("ok"), rows(backup, "PRAGMA quick_check"));
            assertEquals(List.of("1"), rows(backup, "SELECT count(*) FROM analysis_case"));
        }
    }

    @Test
    void deletesOnlyItsStagingFileWhenVerificationFails() throws Exception {
        var source = new SqliteConnectionFactory(directory.resolve("empty.db"));
        try (var ignored = source.open()) {
            // Missing schema makes the post-backup count verification fail.
        }
        var destination = directory.resolve("backup.db");
        assertThrows(SqliteBackupException.class, () -> new SqliteBackup(source).backupTo(destination));
        assertFalse(Files.exists(destination));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".vigil-backup-")));
        }
    }
}
