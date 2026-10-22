package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static org.junit.jupiter.api.Assertions.*;

class SqliteWalConcurrencyTest {
    @TempDir Path directory;

    @Test
    void readerSeesCommittedSnapshotWhileWriterHoldsLock() throws Exception {
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"));
        migrate(factory);
        try (var writer = factory.open(); var reader = factory.open()) {
            insertCase(writer, "11111111-1111-1111-1111-111111111111");
            execute(writer, "BEGIN IMMEDIATE");
            insertCase(writer, "22222222-2222-2222-2222-222222222222");
            assertEquals(List.of("1"), rows(reader, "SELECT count(*) FROM analysis_case"));
            execute(writer, "COMMIT");
            assertEquals(List.of("2"), rows(reader, "SELECT count(*) FROM analysis_case"));
        }
    }

    @Test
    void secondWriterWaitsForInjectedBusyTimeout() throws Exception {
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"), 200);
        try (var first = factory.open(); var second = factory.open()) {
            execute(first, "BEGIN IMMEDIATE");
            long start = System.nanoTime();
            SQLException failure = assertThrows(SQLException.class, () -> execute(second, "BEGIN IMMEDIATE"));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure instanceof SQLiteException);
            assertEquals(SQLiteErrorCode.SQLITE_BUSY, ((SQLiteException) failure).getResultCode());
            assertTrue(millis >= 150, "waited " + millis + " ms");
            execute(first, "ROLLBACK");
        }
    }

    @Test
    void restartPreservesCaseAndEventWithoutReapplyingMigrations() throws Exception {
        var file = directory.resolve("vigil.db");
        var factory = new SqliteConnectionFactory(file);
        migrate(factory);
        List<String> cases;
        List<String> events;
        try (var connection = factory.open()) {
            insertCase(connection, "11111111-1111-1111-1111-111111111111");
            execute(connection, """
                    INSERT INTO case_event (case_id, sequence, from_status, to_status, actor, reason, occurred_at)
                    VALUES ('11111111-1111-1111-1111-111111111111', 1, 'DISCOVERED', 'TRIAGED', 'analyst', 'triage',
                    '2026-09-30T12:00:00.000000000Z')""");
            cases = rows(connection, "SELECT * FROM analysis_case");
            events = rows(connection, "SELECT * FROM case_event");
        }
        try (var reopened = new SqliteConnectionFactory(file).open()) {
            assertEquals(cases, rows(reopened, "SELECT * FROM analysis_case"));
            assertEquals(events, rows(reopened, "SELECT * FROM case_event"));
            assertEquals(List.of(), new SqlMigrationRunner(() -> Instant.EPOCH).migrate(reopened));
        }
    }

    static void migrate(SqliteConnectionFactory factory) throws SQLException {
        try (var connection = factory.open()) {
            new SqlMigrationRunner(() -> Instant.parse("2026-09-30T12:00:00Z")).migrate(connection);
        }
    }

    static void insertCase(java.sql.Connection connection, String id) throws SQLException {
        execute(connection, "INSERT INTO analysis_case (case_id, created_at, status) VALUES ('"
                + id + "', '2026-09-30T12:00:00.000000000Z', 'DISCOVERED')");
    }
}
