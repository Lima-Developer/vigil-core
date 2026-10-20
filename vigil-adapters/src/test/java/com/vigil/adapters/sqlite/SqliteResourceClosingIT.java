package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves connections, statements and result sets are closed on success and on read/write failures. */
class SqliteResourceClosingIT {
    @TempDir
    Path directory;

    private final List<Tracked> tracked = new ArrayList<>();
    private SqliteConnectionFactory plain;
    private SqliteAnalysisCaseRepository repository;

    private record Tracked(String kind, boolean[] closed) {
    }

    @BeforeEach
    void setUp() throws SQLException {
        plain = new SqliteConnectionFactory(directory.resolve("vigil.db"));
        try (var connection = plain.open()) {
            new SqlMigrationRunner(() -> Instant.parse("2026-09-07T10:00:00Z")).migrate(connection);
        }
        repository = new SqliteAnalysisCaseRepository(
                new SqliteConnectionFactory(directory.resolve("vigil.db"), url -> track(DriverManager.getConnection(url), "connection")));
    }

    private <T> T track(T target, String kind) {
        var closed = new boolean[1];
        tracked.add(new Tracked(kind, closed));
        var interfaces = target instanceof Connection ? new Class<?>[]{Connection.class}
                : target instanceof PreparedStatement ? new Class<?>[]{PreparedStatement.class}
                : target instanceof Statement ? new Class<?>[]{Statement.class}
                : new Class<?>[]{ResultSet.class};
        @SuppressWarnings("unchecked")
        var proxy = (T) Proxy.newProxyInstance(getClass().getClassLoader(), interfaces, (self, method, arguments) -> {
            try {
                var result = method.invoke(target, arguments);
                if (method.getName().equals("close")) {
                    closed[0] = true;
                }
                if (result instanceof PreparedStatement statement) {
                    return track(statement, "prepared statement");
                }
                if (result instanceof Statement statement) {
                    return track(statement, "statement");
                }
                if (result instanceof ResultSet resultSet) {
                    return track(resultSet, "result set");
                }
                return result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        return proxy;
    }

    private void assertAllClosed() {
        assertAll(tracked.stream().map(resource ->
                (org.junit.jupiter.api.function.Executable) () -> assertTrue(resource.closed()[0], resource.kind() + " left open")));
    }

    @Test
    void shouldCloseEverythingAfterSuccessfulWritesAndReads() {
        repository.save(AnalysisCaseFixtures.triaged());
        repository.getById(AnalysisCaseFixtures.CASE_ID);
        repository.findByIdempotencyKey("missing");

        assertAll(
                () -> assertTrue(tracked.stream().anyMatch(resource -> resource.kind().equals("result set"))),
                () -> assertTrue(tracked.stream().anyMatch(resource -> resource.kind().equals("prepared statement"))),
                this::assertAllClosed
        );
    }

    @Test
    void shouldCloseEverythingAfterAReadFailure() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        try (var connection = plain.open()) {
            SqliteTestSupport.execute(connection, "DROP TABLE evidence");
        }
        tracked.clear();

        assertThrows(SqlitePersistenceException.class, () -> repository.getById(AnalysisCaseFixtures.CASE_ID));

        assertAll(() -> assertFalse(tracked.isEmpty()), this::assertAllClosed);
    }

    @Test
    void shouldCloseEverythingAfterAWriteFailureAndAfterAConflict() throws SQLException {
        try (var connection = plain.open()) {
            SqliteTestSupport.execute(connection,
                    "CREATE TRIGGER fail BEFORE INSERT ON case_event BEGIN SELECT RAISE(ABORT, 'boom'); END");
        }
        var analysisCase = AnalysisCaseFixtures.triaged();

        assertThrows(SqlitePersistenceException.class, () -> repository.save(analysisCase));

        assertAll(() -> assertFalse(tracked.isEmpty()), this::assertAllClosed);
    }

    @Test
    void shouldCloseEverythingWhenDiscoveryLosesTheKey() {
        repository.saveDiscovered(AnalysisCaseFixtures.discovered(), "key");
        tracked.clear();

        assertThrows(com.vigil.application.exception.ConcurrentCaseUpdate.class, () -> repository.saveDiscovered(
                com.vigil.core.domain.AnalysisCase.discover(java.util.UUID.randomUUID(), AnalysisCaseFixtures.CREATED_AT), "key"));

        assertAll(() -> assertEquals(true, tracked.stream().anyMatch(resource -> resource.kind().equals("connection"))),
                this::assertAllClosed);
    }
}
