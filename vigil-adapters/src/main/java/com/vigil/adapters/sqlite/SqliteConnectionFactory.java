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
        var connection = opener.open("jdbc:sqlite:" + databaseFile);
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
