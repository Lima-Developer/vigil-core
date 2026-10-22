package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK;
import static org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY;
import static org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL;
import static org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY;
import static org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;

class SqliteSchemaTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CASE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_CASE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String AT = "2026-09-30T12:00:00.000000000Z";
    private static final String CASE_COLUMNS = "(case_id, created_at, status, version, query_fingerprint, "
            + "fingerprint_algorithm_version, discovery_idempotency_key)";
    private static final String EVENT_COLUMNS = "(case_id, sequence, from_status, to_status, actor, reason, occurred_at)";

    @TempDir
    Path directory;

    private Connection connection;

    @BeforeEach
    void createSchemaWithASentinelCase() throws SQLException {
        connection = new SqliteConnectionFactory(directory.resolve("vigil.db")).open();
        new SqlMigrationRunner(() -> INSTALLED_AT).migrate(connection);
        insertCase("'%s', '%s', 'TRIAGED', 1, 'fp-1', 'v1', 'discovery-1'".formatted(CASE_ID, AT));
        insertEvent("'%s', 1, 'DISCOVERED', 'TRIAGED', 'analyst', 'slow query', '%s'".formatted(CASE_ID, AT));
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    @Test
    void shouldCreateOnlyTheCaseTablesAndTheMigrationHistory() throws SQLException {
        assertEquals(
                List.of("analysis_case", "case_event", "domain_event", "evidence", "schema_version"),
                rows(connection, "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")
        );
    }

    @Test
    void shouldDeclareAnalysisCaseColumnsWithoutBusinessPayload() throws SQLException {
        assertEquals(List.of(
                "case_id | TEXT | 1 | null | 1",
                "created_at | TEXT | 1 | null | 0",
                "status | TEXT | 1 | null | 0",
                "version | INTEGER | 1 | 0 | 0",
                "query_fingerprint | TEXT | 0 | null | 0",
                "fingerprint_algorithm_version | TEXT | 0 | null | 0",
                "discovery_idempotency_key | TEXT | 0 | null | 0"
        ), rows(connection, "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('analysis_case')"));
    }

    @Test
    void shouldDeclareCaseEventColumnsKeyedByCaseAndSequence() throws SQLException {
        assertEquals(List.of(
                "case_id | TEXT | 1 | null | 1",
                "sequence | INTEGER | 1 | null | 2",
                "from_status | TEXT | 1 | null | 0",
                "to_status | TEXT | 1 | null | 0",
                "actor | TEXT | 1 | null | 0",
                "reason | TEXT | 1 | null | 0",
                "occurred_at | TEXT | 1 | null | 0"
        ), rows(connection, "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('case_event')"));
    }

    @Test
    void shouldDeclareSchemaVersionColumns() throws SQLException {
        assertEquals(List.of(
                "version | INTEGER | 0 | null | 1",
                "description | TEXT | 1 | null | 0",
                "checksum | TEXT | 1 | null | 0",
                "installed_at | TEXT | 1 | null | 0"
        ), rows(connection, "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('schema_version')"));
    }

    @Test
    void shouldRestrictDeletionOfCasesReferencedByEvents() throws SQLException {
        assertEquals(
                List.of("analysis_case | case_id | case_id | NO ACTION | RESTRICT"),
                rows(connection, "SELECT \"table\", \"from\", \"to\", on_update, on_delete "
                        + "FROM pragma_foreign_key_list('case_event')")
        );
    }

    @Test
    void shouldCreateExactlyTheRequestedExplicitIndexes() throws SQLException {
        assertEquals(List.of(
                "idx_analysis_case_created_at | analysis_case | created_at",
                "idx_analysis_case_status_created_at | analysis_case | status,created_at",
                "idx_case_event_case_id_occurred_at | case_event | case_id,occurred_at",
                "idx_case_event_occurred_at | case_event | occurred_at",
                "idx_domain_event_occurred_at | domain_event | occurred_at",
                "idx_evidence_captured_at | evidence | captured_at"
        ), rows(connection, """
                SELECT s.name, s.tbl_name, (SELECT group_concat(name, ',') FROM
                    (SELECT name FROM pragma_index_info(s.name) ORDER BY seqno))
                FROM sqlite_schema s
                WHERE s.type = 'index' AND s.sql IS NOT NULL
                ORDER BY s.name"""));
    }

    @Test
    void shouldCoverKeysWithImplicitIndexesAndNoRedundantCaseIdIndex() throws SQLException {
        var indexes = """
                SELECT l."unique", l.origin, (SELECT group_concat(name, ',') FROM
                    (SELECT name FROM pragma_index_info(l.name) ORDER BY seqno))
                FROM pragma_index_list('%s') l
                WHERE l.origin <> 'c'
                ORDER BY 3""";

        assertAll(
                () -> assertEquals(
                        List.of("1 | pk | case_id", "1 | u | discovery_idempotency_key"),
                        rows(connection, indexes.formatted("analysis_case"))
                ),
                () -> assertEquals(
                        List.of("1 | pk | case_id,sequence"),
                        rows(connection, indexes.formatted("case_event"))
                ),
                () -> assertFalse(rows(connection, """
                        SELECT (SELECT group_concat(name, ',') FROM pragma_index_info(l.name))
                        FROM pragma_index_list('case_event') l""").contains("case_id"))
        );
    }

    @Test
    void shouldAcceptValidCasesAndOrderedEventsSharingTheSameInstant() throws SQLException {
        insertCase("'%s', '%s', 'DISCOVERED', 0, 'fp-1', 'v1', NULL".formatted(OTHER_CASE_ID, AT));
        execute(connection, "INSERT INTO analysis_case (case_id, created_at, status) "
                + "VALUES ('33333333-3333-3333-3333-333333333333', '%s', 'DISCOVERED')".formatted(AT));
        insertEvent("'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', 'collector', 'Evidence collected.', '%s'"
                .formatted(CASE_ID, AT));
        execute(connection, "UPDATE analysis_case SET version = version + 1 WHERE case_id = '%s'".formatted(CASE_ID));

        assertAll(
                () -> assertEquals(List.of(
                        CASE_ID + " | 2 | fp-1 | v1 | discovery-1",
                        OTHER_CASE_ID + " | 0 | fp-1 | v1 | null",
                        "33333333-3333-3333-3333-333333333333 | 0 | null | null | null"
                ), rows(connection, "SELECT case_id, version, query_fingerprint, fingerprint_algorithm_version, "
                        + "discovery_idempotency_key FROM analysis_case ORDER BY case_id")),
                () -> assertEquals(List.of(
                        "1 | DISCOVERED | TRIAGED",
                        "2 | TRIAGED | EVIDENCE_COLLECTED"
                ), rows(connection, "SELECT sequence, from_status, to_status FROM case_event "
                        + "WHERE case_id = '%s' ORDER BY sequence".formatted(CASE_ID))),
                () -> assertEquals(List.of(), rows(connection, "PRAGMA foreign_key_check"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void shouldRejectInvalidAnalysisCases(String rule, String values, SQLiteErrorCode expected) {
        var failure = assertThrows(SQLiteException.class, () -> insertCase(values));

        assertEquals(expected, failure.getResultCode(), failure.getMessage());
    }

    static Stream<Arguments> invalidCases() {
        return Stream.of(
                Arguments.of("null id", "NULL, '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("empty id", "'', '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("blank id", "' ', '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("blob id", "X'01', '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("duplicate id", "'%s', '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_PRIMARYKEY),
                Arguments.of("null created_at", "'%s', NULL, 'DISCOVERED', 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("non canonical created_at", "'%s', '2026-09-30T12:00:00Z', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("null status", "'%s', '%s', NULL, 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("unknown status", "'%s', '%s', 'CLOSED', 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("lowercase status", "'%s', '%s', 'discovered', 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("null version", "'%s', '%s', 'DISCOVERED', NULL, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("negative version", "'%s', '%s', 'DISCOVERED', -1, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("fractional version", "'%s', '%s', 'DISCOVERED', 1.5, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("text version", "'%s', '%s', 'DISCOVERED', 'one', NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("fingerprint without algorithm", "'%s', '%s', 'DISCOVERED', 0, 'fp-2', NULL, NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("algorithm without fingerprint", "'%s', '%s', 'DISCOVERED', 0, NULL, 'v1', NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("empty fingerprint", "'%s', '%s', 'DISCOVERED', 0, '', 'v1', NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("empty algorithm", "'%s', '%s', 'DISCOVERED', 0, 'fp-2', ' ', NULL".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("empty discovery key", "'%s', '%s', 'DISCOVERED', 0, NULL, NULL, ''".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("duplicate discovery key", "'%s', '%s', 'DISCOVERED', 0, NULL, NULL, 'discovery-1'".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_UNIQUE)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    void shouldRejectInvalidCaseEvents(String rule, String values, SQLiteErrorCode expected) {
        var failure = assertThrows(SQLiteException.class, () -> insertEvent(values));

        assertEquals(expected, failure.getResultCode(), failure.getMessage());
    }

    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                Arguments.of("null case", "NULL, 2, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("orphan case", "'%s', 1, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(OTHER_CASE_ID, AT), SQLITE_CONSTRAINT_FOREIGNKEY),
                Arguments.of("repeated sequence", "'%s', 1, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_PRIMARYKEY),
                Arguments.of("null sequence", "'%s', NULL, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("zero sequence", "'%s', 0, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("negative sequence", "'%s', -1, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("fractional sequence", "'%s', 2.5, 'DISCOVERED', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("unknown from_status", "'%s', 2, 'OPEN', 'TRIAGED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("unknown to_status", "'%s', 2, 'TRIAGED', 'CLOSED', 'a', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("null actor", "'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', NULL, 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("blank actor", "'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', ' ', 'r', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("empty reason", "'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', 'a', '', '%s'".formatted(CASE_ID, AT), SQLITE_CONSTRAINT_CHECK),
                Arguments.of("null occurred_at", "'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', 'a', 'r', NULL".formatted(CASE_ID), SQLITE_CONSTRAINT_NOTNULL),
                Arguments.of("offset occurred_at", "'%s', 2, 'TRIAGED', 'EVIDENCE_COLLECTED', 'a', 'r', '2026-09-30T12:00:00.000000000+00:00'".formatted(CASE_ID), SQLITE_CONSTRAINT_CHECK)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidHistoryEntries")
    void shouldRejectInvalidHistoryEntries(String rule, String values) {
        var failure = assertThrows(SQLiteException.class, () -> execute(connection,
                "INSERT INTO schema_version (version, description, checksum, installed_at) VALUES (" + values + ")"));

        assertEquals(SQLITE_CONSTRAINT_CHECK, failure.getResultCode(), failure.getMessage());
    }

    static Stream<Arguments> invalidHistoryEntries() {
        var checksum = "0".repeat(64);
        return Stream.of(
                Arguments.of("zero version", "0, 'd', '%s', '%s'".formatted(checksum, AT)),
                Arguments.of("negative version", "-1, 'd', '%s', '%s'".formatted(checksum, AT)),
                Arguments.of("blank description", "2, ' ', '%s', '%s'".formatted(checksum, AT)),
                Arguments.of("short checksum", "2, 'd', 'abc', '%s'".formatted(AT)),
                Arguments.of("uppercase checksum", "2, 'd', '%s', '%s'".formatted("A".repeat(64), AT)),
                Arguments.of("non canonical installed_at", "2, 'd', '%s', '2026-09-30T12:00:00.000Z'".formatted(checksum))
        );
    }

    @Test
    void shouldRestrictDeletingACaseWithEventsButAllowDeletingOneWithout() throws SQLException {
        insertCase("'%s', '%s', 'DISCOVERED', 0, NULL, NULL, NULL".formatted(OTHER_CASE_ID, AT));

        var failure = assertThrows(SQLiteException.class, () -> execute(connection,
                "DELETE FROM analysis_case WHERE case_id = '%s'".formatted(CASE_ID)));
        execute(connection, "DELETE FROM analysis_case WHERE case_id = '%s'".formatted(OTHER_CASE_ID));

        // SQLite enforces RESTRICT immediately through an internal trigger; NO ACTION would report FOREIGNKEY instead.
        assertAll(
                () -> assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, failure.getResultCode()),
                () -> assertTrue(failure.getMessage().contains("FOREIGN KEY constraint failed"), failure.getMessage()),
                () -> assertEquals(List.of(CASE_ID), rows(connection, "SELECT case_id FROM analysis_case")),
                () -> assertEquals(List.of(), rows(connection, "PRAGMA foreign_key_check"))
        );
    }

    private void insertCase(String values) throws SQLException {
        execute(connection, "INSERT INTO analysis_case " + CASE_COLUMNS + " VALUES (" + values + ")");
    }

    private void insertEvent(String values) throws SQLException {
        execute(connection, "INSERT INTO case_event " + EVENT_COLUMNS + " VALUES (" + values + ")");
    }
}
