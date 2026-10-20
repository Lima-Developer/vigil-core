package com.vigil.adapters.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Opens connections to the local SQLite file with the invariants every Vigil connection relies on:
 * {@code foreign_keys} enabled and WAL journaling, both verified before the connection is returned.
 * <p>
 * The caller owns each returned connection and must close it. The connection is returned in auto-commit mode,
 * so no transaction is open while the pragmas are applied.
 */
public final class SqliteConnectionFactory {
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path databaseFile;
    private final ConnectionOpener opener;

    public SqliteConnectionFactory(Path databaseFile) {
        this(databaseFile, url -> DriverManager.getConnection(url));
    }

    SqliteConnectionFactory(Path databaseFile, ConnectionOpener opener) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "Database file must not be null.")
                .toAbsolutePath()
                .normalize();
        this.opener = Objects.requireNonNull(opener, "Connection opener must not be null.");
    }

    public Connection open() throws SQLException {
        return open("jdbc:sqlite:" + databaseFile);
    }

    /**
     * Like {@link #open()}, but transactions started with {@code setAutoCommit(false)} are {@code BEGIN IMMEDIATE}:
     * the write lock is taken up front, so a read-then-write unit of work never fails on a stale snapshot.
     */
    public Connection openForWrite() throws SQLException {
        return open("jdbc:sqlite:" + databaseFile + "?transaction_mode=IMMEDIATE");
    }

    private Connection open(String url) throws SQLException {
        var connection = opener.open(url);
        try {
            configure(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public Path databaseFile() {
        return databaseFile;
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
            requirePragma(statement, "PRAGMA busy_timeout", String.valueOf(BUSY_TIMEOUT_MILLIS));
            statement.execute("PRAGMA foreign_keys = ON");
            requirePragma(statement, "PRAGMA foreign_keys", "1");
            requirePragma(statement, "PRAGMA journal_mode = WAL", "wal");
        }
    }

    private static void requirePragma(Statement statement, String pragma, String expected) throws SQLException {
        try (var result = statement.executeQuery(pragma)) {
            var actual = result.next() ? result.getString(1) : null;
            if (!expected.equalsIgnoreCase(actual)) {
                throw new SQLException("SQLite %s returned %s, expected %s.".formatted(pragma, actual, expected));
            }
        }
    }

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url) throws SQLException;
    }
}
