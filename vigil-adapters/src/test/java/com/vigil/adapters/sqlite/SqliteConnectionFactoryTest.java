package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.failing;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConnectionFactoryTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CASE_ID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path directory;

    @Test
    void shouldOpenTheLocalFileWithForeignKeysAndWalOutsideATransaction() throws SQLException {
        var databaseFile = directory.resolve("vigil.db");
        var factory = new SqliteConnectionFactory(databaseFile);

        try (var connection = factory.open()) {
            assertAll(
                    () -> assertEquals(List.of("1"), rows(connection, "PRAGMA foreign_keys")),
                    () -> assertEquals(List.of("wal"), rows(connection, "PRAGMA journal_mode")),
                    () -> assertEquals(List.of("5000"), rows(connection, "PRAGMA busy_timeout")),
                    () -> assertTrue(connection.getAutoCommit()),
                    () -> assertTrue(Files.exists(databaseFile)),
                    () -> assertEquals(databaseFile.toAbsolutePath().normalize(), factory.databaseFile())
            );
        }
    }

    @Test
    void shouldRejectAConnectionWhenBusyTimeoutWasNotActuallyApplied() throws SQLException {
        var opened = new ArrayList<Connection>();
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"), url -> {
            var connection = DriverManager.getConnection(url);
            execute(connection, "PRAGMA busy_timeout = 1");
            opened.add(connection);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("createStatement")) {
                            var statement = connection.createStatement();
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                    new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().equals("execute") && statementArgs != null
                                                && statementArgs[0] instanceof String sql
                                                && sql.equals("PRAGMA busy_timeout = 5000")) {
                                            return false;
                                        }
                                        try {
                                            return statementMethod.invoke(statement, statementArgs);
                                        } catch (InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        });

        var failure = assertThrows(SQLException.class, factory::open);

        assertAll(
                () -> assertTrue(failure.getMessage().contains("busy_timeout")),
                () -> assertTrue(opened.getFirst().isClosed())
        );
    }

    @Test
    void shouldConfigureEveryIndependentConnection() throws SQLException {
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"));

        try (var writer = factory.open(); var reader = factory.open()) {
            new SqlMigrationRunner(() -> INSTALLED_AT).migrate(writer);
            execute(writer, """
                    INSERT INTO analysis_case (case_id, created_at, status)
                    VALUES ('%s', '2026-09-30T12:00:00.000000000Z', 'DISCOVERED')""".formatted(CASE_ID));

            var orphan = assertThrows(SQLiteException.class, () -> execute(reader, """
                    INSERT INTO case_event (case_id, sequence, from_status, to_status, actor, reason, occurred_at)
                    VALUES ('99999999-9999-9999-9999-999999999999', 1, 'DISCOVERED', 'TRIAGED', 'analyst', 'triage',
                            '2026-09-30T12:00:00.000000000Z')"""));

            assertAll(
                    () -> assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, orphan.getResultCode()),
                    () -> assertEquals(List.of("1"), rows(reader, "PRAGMA foreign_keys")),
                    () -> assertEquals(List.of("wal"), rows(reader, "PRAGMA journal_mode")),
                    () -> assertEquals(List.of(CASE_ID), rows(reader, "SELECT case_id FROM analysis_case")),
                    () -> assertEquals(List.of(), rows(reader, "PRAGMA foreign_key_check"))
            );
        }
    }

    @Test
    void shouldCloseTheConnectionWhenWalCannotBeEnabled() {
        var opened = new ArrayList<Connection>();
        var factory = new SqliteConnectionFactory(directory.resolve("ignored.db"), url -> {
            var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            opened.add(connection);
            return connection;
        });

        var failure = assertThrows(SQLException.class, factory::open);

        assertAll(
                () -> assertTrue(failure.getMessage().contains("journal_mode"), failure.getMessage()),
                () -> assertTrue(opened.getFirst().isClosed())
        );
    }

    @Test
    void shouldRejectAConnectionWhoseForeignKeysCannotBeEnabled() {
        var opened = new ArrayList<Connection>();
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"), url -> {
            var connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);
            execute(connection, "SELECT 1");
            opened.add(connection);
            return connection;
        });

        var failure = assertThrows(SQLException.class, factory::open);

        assertAll(
                () -> assertTrue(failure.getMessage().contains("foreign_keys"), failure.getMessage()),
                () -> assertTrue(opened.getFirst().isClosed())
        );
    }

    @Test
    void shouldKeepTheConfigurationFailureWhenClosingAlsoFails() {
        var factory = new SqliteConnectionFactory(directory.resolve("ignored.db"), url -> failing(
                DriverManager.getConnection("jdbc:sqlite::memory:"),
                (method, arguments) -> method.equals("close")
        ));

        var failure = assertThrows(SQLException.class, factory::open);

        assertAll(
                () -> assertTrue(failure.getMessage().contains("journal_mode"), failure.getMessage()),
                () -> assertEquals(1, failure.getSuppressed().length),
                () -> assertEquals("Injected close failure.", failure.getSuppressed()[0].getMessage())
        );
    }

    @Test
    void shouldRequireADatabaseFile() {
        assertThrows(NullPointerException.class, () -> new SqliteConnectionFactory(null));
    }
}
