package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import com.vigil.application.port.out.interfaces.Clock;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Prepares a SQLite file for use: opens it, applies the pending migrations and closes the connection, even when
 * migrating fails. Lets composition roots obtain a ready {@link SqliteConnectionFactory} without touching JDBC.
 */
public final class SqliteDatabaseInitializer {

    private SqliteDatabaseInitializer() {
    }

    /**
     * @throws SqlitePersistenceException if the file cannot be opened
     * @throws com.vigil.adapters.sqlite.migration.MigrationException if a migration fails
     */
    public static SqliteConnectionFactory initialize(Path databaseFile, Clock clock) {
        Objects.requireNonNull(clock, "Clock must not be null.");
        var connections = new SqliteConnectionFactory(databaseFile);
        try (var connection = connections.open()) {
            new SqlMigrationRunner(clock).migrate(connection);
        } catch (SQLException failure) {
            throw new SqlitePersistenceException("SQLite database could not be initialized.", failure);
        }
        return connections;
    }
}
