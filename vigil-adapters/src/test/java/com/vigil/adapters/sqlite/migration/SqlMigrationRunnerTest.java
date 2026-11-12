package com.vigil.adapters.sqlite.migration;

import com.vigil.adapters.sqlite.SqliteConnectionFactory;
import com.vigil.adapters.sqlite.SqliteInstantCodec;
import com.vigil.application.port.out.interfaces.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.failing;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static com.vigil.adapters.sqlite.SqliteTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMigrationRunnerTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-30T12:34:56.123456789Z");
    private static final Clock CLOCK = () -> INSTALLED_AT;
    private static final String CASE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String FIXTURES = "db/migration-fixtures/";

    private static final Migration V001 = SqlMigrationRunner.catalog().getFirst();
    private static final Migration V002 = fixture(2, "create fixture note", "V002__create_fixture_note.sql");
    private static final Migration V002_FAILING = fixture(2, "create fixture note", "V002__fail_after_ddl_and_dml.sql");
    private static final Migration V003 = fixture(3, "create fixture tag", "V003__create_fixture_tag.sql");

    @TempDir
    Path directory;

    private SqliteConnectionFactory factory;

    @BeforeEach
    void createFactory() {
        factory = new SqliteConnectionFactory(directory.resolve("vigil.db"));
    }

    @Test
    void shouldBringAnEmptyDatabaseToTheCurrentVersionWithTheShippedHistory() throws Exception {
        var applied = migrate(new SqlMigrationRunner(CLOCK));

        try (var connection = factory.open()) {
            assertAll(
                    () -> assertEquals(List.of(1, 2, 3), applied),
                    () -> assertEquals(List.of(
                            "1 | create case schema | " + sha256("db/migration/V001__create_case_schema.sql")
                                    + " | 2026-09-30T12:34:56.123456789Z",
                            "2 | create evidence and domain event schema | "
                                    + sha256("db/migration/V002__create_evidence_and_domain_event_schema.sql")
                                    + " | 2026-09-30T12:34:56.123456789Z",
                            "3 | create workspace telemetry schema | "
                                    + sha256("db/migration/V003__create_workspace_telemetry_schema.sql")
                                    + " | 2026-09-30T12:34:56.123456789Z"
                    ), rows(connection, "SELECT version, description, checksum, installed_at FROM schema_version")),
                    () -> assertEquals(
                            List.of("analysis_case", "case_event", "domain_event", "evidence", "schema_version",
                                    "workspace_telemetry_sample"),
                            rows(connection, "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")
                    ),
                    () -> assertTrue(connection.getAutoCommit())
            );
        }
    }

    @Test
    void shouldLeaveSchemaHistoryAndDataUntouchedWhenRerunAfterReopening() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        insertSentinelCase();
        var before = snapshotOf();

        var applied = migrate(new SqlMigrationRunner(List.of(V001), () -> Instant.parse("2027-01-01T00:00:00Z")));

        assertAll(
                () -> assertEquals(List.of(), applied),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @Test
    void shouldApplyOnlyPendingMigrationsInNumericOrderPreservingData() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        insertSentinelCase();

        var applied = migrate(new SqlMigrationRunner(List.of(V003, V002, V001), CLOCK));

        try (var connection = factory.open()) {
            assertAll(
                    () -> assertEquals(List.of(2, 3), applied),
                    () -> assertEquals(List.of("1", "2", "3"), rows(connection, "SELECT version FROM schema_version ORDER BY version")),
                    () -> assertEquals(List.of(CASE_ID + " | 0"), rows(connection, "SELECT case_id, version FROM analysis_case")),
                    () -> assertEquals(List.of(), rows(connection, "SELECT tag FROM fixture_tag"))
            );
        }
    }

    @Test
    void shouldApplyAnOutOfOrderCatalogOnAnEmptyDatabase() throws Exception {
        var applied = migrate(new SqlMigrationRunner(List.of(V002, V001), CLOCK));

        try (var connection = factory.open()) {
            assertAll(
                    () -> assertEquals(List.of(1, 2), applied),
                    () -> assertEquals(List.of("1", "2"), rows(connection, "SELECT version FROM schema_version ORDER BY version"))
            );
        }
    }

    @Test
    void shouldAcceptNonConsecutiveVersions() throws Exception {
        var applied = migrate(new SqlMigrationRunner(List.of(V001, V003), CLOCK));

        assertEquals(List.of(1, 3), applied);
        assertEquals(List.of(), migrate(new SqlMigrationRunner(List.of(V003, V001), CLOCK)));
    }

    @Test
    void shouldExecuteDelimitedStatementsKeepingSemicolonsInsideLiterals() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001, V002), CLOCK));

        try (var connection = factory.open()) {
            assertEquals(
                    List.of("first; second;\n-- vigil:statement-end is only a delimiter when alone on its line"),
                    rows(connection, "SELECT body FROM fixture_note")
            );
        }
    }

    @Test
    void shouldRollBackAFailedUpdateAndAllowACorrectedRetry() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        insertSentinelCase();
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, V002_FAILING), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("V002"), failure.getMessage()),
                () -> assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                        assertInstanceOf(SQLiteException.class, failure.getCause()).getResultCode()),
                () -> assertEquals(before, snapshotOf())
        );
        assertEquals(List.of(2), migrate(new SqlMigrationRunner(List.of(V001, V002), CLOCK)));
    }

    @Test
    void shouldRollBackTheFirstMigrationTogetherWithTheHistoryTable() throws Exception {
        var failing = fixture(1, "create case schema", "V001__fail_initial.sql");

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(failing), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("V001"), failure.getMessage()),
                () -> assertInstanceOf(SQLException.class, failure.getCause()),
                () -> assertEquals(List.of(), snapshotOf())
        );
        assertEquals(List.of(1), migrate(new SqlMigrationRunner(List.of(V001), CLOCK)));
    }

    @Test
    void shouldRollBackWhenCommitFails() throws Exception {
        try (var connection = factory.open()) {
            var failingCommit = failing(connection, (method, arguments) -> method.equals("commit"));

            var failure = assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(failingCommit));

            assertAll(
                    () -> assertEquals("Injected commit failure.", failure.getCause().getMessage()),
                    () -> assertTrue(connection.getAutoCommit())
            );
        }
        assertEquals(List.of(), snapshotOf());
    }

    @Test
    void shouldPreserveTheOriginalCauseWhenRollbackFails() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        insertSentinelCase();
        var before = snapshotOf();

        try (var connection = factory.open()) {
            var failingRollback = failing(connection, (method, arguments) -> method.equals("rollback"));

            var failure = assertThrows(MigrationException.class,
                    () -> new SqlMigrationRunner(List.of(V001, V002_FAILING), CLOCK).migrate(failingRollback));

            assertAll(
                    () -> assertInstanceOf(SQLiteException.class, failure.getCause()),
                    () -> assertEquals(1, failure.getSuppressed().length),
                    () -> assertEquals("Injected rollback failure.", failure.getSuppressed()[0].getMessage())
            );
        }
        assertEquals(before, snapshotOf());
    }

    @Test
    void shouldReportAFailureToRestoreAutoCommit() throws Exception {
        try (var connection = factory.open()) {
            var failingRestore = failing(connection,
                    (method, arguments) -> method.equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]));

            var failure = assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(failingRestore));

            assertEquals("Injected setAutoCommit failure.", failure.getCause().getMessage());
            connection.setAutoCommit(true);
            assertEquals(List.of("1"), rows(connection, "SELECT version FROM schema_version"));
        }
    }

    @Test
    void shouldKeepTheMigrationFailureWhenAutoCommitCannotBeRestored() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));

        try (var connection = factory.open()) {
            var failingRestore = failing(connection,
                    (method, arguments) -> method.equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]));

            var failure = assertThrows(MigrationException.class,
                    () -> new SqlMigrationRunner(List.of(V001, V002_FAILING), CLOCK).migrate(failingRestore));

            assertAll(
                    () -> assertInstanceOf(SQLiteException.class, failure.getCause()),
                    () -> assertEquals("Injected setAutoCommit failure.", failure.getSuppressed()[0].getMessage())
            );
        }
    }

    @Test
    void shouldReportAFailureToStartTheTransaction() throws Exception {
        try (var connection = factory.open()) {
            var failingStart = failing(connection, (method, arguments) -> method.equals("setAutoCommit"));

            var failure = assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(failingStart));

            assertEquals("Injected setAutoCommit failure.", failure.getCause().getMessage());
        }
        assertEquals(List.of(), snapshotOf());
    }

    @Test
    void shouldRejectAChecksumThatDiffersFromTheCatalog() throws Exception {
        assertRejectedAfterTampering("UPDATE schema_version SET checksum = '%s'".formatted("0".repeat(64)), "Checksum");
    }

    @Test
    void shouldRejectADescriptionThatDiffersFromTheCatalog() throws Exception {
        assertRejectedAfterTampering("UPDATE schema_version SET description = 'renamed'", "Description");
    }

    @Test
    void shouldRejectAnInstallationInstantThatIsNotCanonical() throws Exception {
        assertRejectedAfterTampering("UPDATE schema_version SET installed_at = '2026-02-30T00:00:00.000000000Z'", "installed_at");
    }

    @Test
    void shouldRejectADatabaseNewerThanTheCatalog() throws Exception {
        assertRejectedAfterTampering(historyEntry(99), "newer");
    }

    @Test
    void shouldRejectAHistoryTableWithoutEntries() throws Exception {
        assertRejectedAfterTampering("DELETE FROM schema_version", "no migration");
    }

    @Test
    void shouldRejectAHistoryTableWithUnexpectedStructure() throws Exception {
        try (var connection = factory.open()) {
            execute(connection, "CREATE TABLE schema_version (version INTEGER PRIMARY KEY)");
            execute(connection, "INSERT INTO schema_version (version) VALUES (1)");
        }
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class, () -> migrate(new SqlMigrationRunner(List.of(V001), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("history"), failure.getMessage()),
                () -> assertInstanceOf(SQLException.class, failure.getCause()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @Test
    void shouldRejectAHistoryThatSkipsACatalogVersion() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        try (var connection = factory.open()) {
            execute(connection, historyEntry(3));
        }
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, V002, V003), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("found V003 where V002 was expected"), failure.getMessage()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @Test
    void shouldRejectAHistoryVersionUnknownToTheCatalog() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001, V002), CLOCK));
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, V003), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("found V002 where V003 was expected"), failure.getMessage()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @Test
    void shouldRejectAMissingResourceBeforeChangingTheDatabase() throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        var before = snapshotOf();
        var missing = fixture(2, "missing", "V002__does_not_exist.sql");

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, missing), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("not found"), failure.getMessage()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "V002__transaction_control.sql, must not control transactions (COMMIT)",
            "V002__empty_segment.sql,       empty statement segment",
            "V002__invalid_utf8.sql,        not valid UTF-8"
    })
    void shouldRejectMalformedScriptsBeforeChangingTheDatabase(String resource, String reason) throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, fixture(2, "malformed", resource)), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains(reason), failure.getMessage()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    @Test
    void shouldKeepEarlierMigrationsCommittedWhenALaterOneFailsInTheSameRun() throws Exception {
        assertThrows(MigrationException.class,
                () -> migrate(new SqlMigrationRunner(List.of(V001, V002_FAILING), CLOCK)));

        try (var connection = factory.open()) {
            assertAll(
                    () -> assertEquals(List.of("1"), rows(connection, "SELECT version FROM schema_version")),
                    () -> assertEquals(
                            List.of("analysis_case", "case_event", "schema_version"),
                            rows(connection, "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")
                    )
            );
        }
    }

    @Test
    void shouldShipTheCaseAndEvidenceSchemaMigrations() {
        assertEquals(
                List.of(new Migration(1, "create case schema", "db/migration/V001__create_case_schema.sql"),
                        new Migration(2, "create evidence and domain event schema",
                                "db/migration/V002__create_evidence_and_domain_event_schema.sql"),
                        new Migration(3, "create workspace telemetry schema",
                                "db/migration/V003__create_workspace_telemetry_schema.sql")),
                SqlMigrationRunner.catalog()
        );
    }

    @Test
    void shouldRejectInvalidCatalogs() {
        assertAll(
                () -> assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(), CLOCK)),
                () -> assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001, V002, V002_FAILING), CLOCK)),
                () -> assertThrows(NullPointerException.class, () -> new SqlMigrationRunner(null, CLOCK)),
                () -> assertThrows(NullPointerException.class, () -> new SqlMigrationRunner(null))
        );
    }

    @Test
    void shouldRejectConnectionsThatAreNotReadyForMigrations() throws Exception {
        try (var inTransaction = factory.open();
             var withoutForeignKeys = DriverManager.getConnection("jdbc:sqlite:" + factory.databaseFile())) {
            inTransaction.setAutoCommit(false);

            assertAll(
                    () -> assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(inTransaction)),
                    () -> assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(withoutForeignKeys)),
                    () -> assertThrows(NullPointerException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(null))
            );
        }
        assertEquals(List.of(), snapshotOf());
    }

    @Test
    void shouldReportAConnectionThatCannotBeInspected() throws Exception {
        try (var connection = factory.open()) {
            var closed = failing(connection, (method, arguments) -> method.equals("getAutoCommit"));

            var failure = assertThrows(MigrationException.class, () -> new SqlMigrationRunner(List.of(V001), CLOCK).migrate(closed));

            assertEquals("Injected getAutoCommit failure.", failure.getCause().getMessage());
        }
    }

    @Test
    void shouldRejectMigrationsWithInvalidMetadata() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(0, "d", "r")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(-1, "d", "r")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(1, " ", "r")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(1, null, "r")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(1, "d", "")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Migration(1, "d", null))
        );
    }

    private void assertRejectedAfterTampering(String tampering, String expectedMessage) throws Exception {
        migrate(new SqlMigrationRunner(List.of(V001), CLOCK));
        try (var connection = factory.open()) {
            execute(connection, tampering);
        }
        var before = snapshotOf();

        var failure = assertThrows(MigrationException.class, () -> migrate(new SqlMigrationRunner(List.of(V001), CLOCK)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage()),
                () -> assertEquals(before, snapshotOf())
        );
    }

    private List<Integer> migrate(SqlMigrationRunner runner) throws SQLException {
        try (var connection = factory.open()) {
            return runner.migrate(connection);
        }
    }

    private List<String> snapshotOf() throws SQLException {
        try (var connection = factory.open()) {
            return snapshot(connection);
        }
    }

    private void insertSentinelCase() throws SQLException {
        try (var connection = factory.open()) {
            execute(connection, """
                    INSERT INTO analysis_case (case_id, created_at, status, query_fingerprint,
                                               fingerprint_algorithm_version, discovery_idempotency_key)
                    VALUES ('%s', '2026-09-30T12:00:00.000000000Z', 'TRIAGED', 'fp-1', 'v1', 'discovery-1')"""
                    .formatted(CASE_ID));
            execute(connection, """
                    INSERT INTO case_event (case_id, sequence, from_status, to_status, actor, reason, occurred_at)
                    VALUES ('%s', 1, 'DISCOVERED', 'TRIAGED', 'analyst', 'slow; query', '2026-09-30T12:00:00.000000000Z')"""
                    .formatted(CASE_ID));
        }
    }

    private static String historyEntry(int version) {
        return "INSERT INTO schema_version (version, description, checksum, installed_at) VALUES (%d, 'future', '%s', '%s')"
                .formatted(version, "0".repeat(64), SqliteInstantCodec.encode(INSTALLED_AT));
    }

    private static Migration fixture(int version, String description, String file) {
        return new Migration(version, description, FIXTURES + file);
    }

    private static String sha256(String resource) throws IOException, NoSuchAlgorithmException {
        try (InputStream input = Objects.requireNonNull(
                SqlMigrationRunnerTest.class.getClassLoader().getResourceAsStream(resource))) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
