package com.vigil.adapters.sqlite.migration;

import com.vigil.adapters.sqlite.SqliteInstantCodec;
import com.vigil.application.port.out.interfaces.Clock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Applies versioned SQL scripts from an explicit catalog, one JDBC transaction per migration.
 * <p>
 * The history in {@code schema_version} must be a prefix of the catalog ordered by version; divergences are
 * rejected, never repaired. {@code schema_version} itself is created by {@value #SCHEMA_VERSION_RESOURCE} in the
 * same transaction as the first functional migration and is not recorded in the history.
 * <p>
 * Scripts hold one complete statement per segment; segments are separated by a line containing only
 * {@value #STATEMENT_DELIMITER}. Scripts must not control transactions.
 */
public final class SqlMigrationRunner {
    public static final String STATEMENT_DELIMITER = "-- vigil:statement-end";
    static final String SCHEMA_VERSION_RESOURCE = "db/migration/V000__create_schema_version.sql";

    private static final List<Migration> CATALOG = List.of(
            new Migration(1, "create case schema", "db/migration/V001__create_case_schema.sql"),
            new Migration(2, "create evidence and domain event schema",
                    "db/migration/V002__create_evidence_and_domain_event_schema.sql")
    );
    private static final Set<String> TRANSACTION_KEYWORDS =
            Set.of("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE");

    private final List<Migration> catalog;
    private final Clock clock;

    public SqlMigrationRunner(Clock clock) {
        this(CATALOG, clock);
    }

    public SqlMigrationRunner(List<Migration> catalog, Clock clock) {
        Objects.requireNonNull(catalog, "Migration catalog must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");

        if (catalog.isEmpty()) {
            throw new MigrationException("Migration catalog must not be empty.");
        }

        var ordered = catalog.stream()
                .map(migration -> Objects.requireNonNull(migration, "Migration must not be null."))
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index).version() == ordered.get(index - 1).version()) {
                throw new MigrationException("Migration catalog declares V%03d more than once."
                        .formatted(ordered.get(index).version()));
            }
        }
        this.catalog = ordered;
    }

    /** Functional migrations shipped with Vigil, in version order. */
    public static List<Migration> catalog() {
        return CATALOG;
    }

    /**
     * Brings the database behind {@code connection} to the latest catalog version.
     * <p>
     * The caller owns the connection and keeps it open afterwards; it must come from
     * {@link com.vigil.adapters.sqlite.SqliteConnectionFactory} or equivalently have {@code foreign_keys} enabled
     * and no open transaction. Auto-commit is restored before returning, except when a rollback fails: the
     * connection is then left untouched and must be closed, which makes SQLite discard the open transaction.
     *
     * @return versions applied by this call, in order; empty when the database is already current
     */
    public List<Integer> migrate(Connection connection) {
        Objects.requireNonNull(connection, "Connection must not be null.");

        var bootstrap = load(SCHEMA_VERSION_RESOURCE);
        var scripts = catalog.stream().map(migration -> new Script(migration, load(migration.resource()))).toList();

        requireReady(connection);
        return applyPending(connection, bootstrap, scripts);
    }

    private List<Integer> applyPending(Connection connection, LoadedResource bootstrap, List<Script> scripts) {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException failure) {
            throw new MigrationException("Could not start a migration transaction.", failure);
        }

        var applied = new ArrayList<Integer>();
        Migration current = null;
        try {
            var historyExists = historyTableExists(connection);
            var history = historyExists ? readHistory(connection) : List.<HistoryEntry>of();
            if (historyExists && history.isEmpty()) {
                throw new MigrationException("Table schema_version exists but records no migration.");
            }

            var createHistory = !historyExists;
            for (var script : pending(history, scripts)) {
                current = script.migration();
                if (createHistory) {
                    execute(connection, bootstrap);
                    createHistory = false;
                }
                execute(connection, script.resource());
                record(connection, script);
                connection.commit();
                applied.add(current.version());
            }
        } catch (SQLException | RuntimeException cause) {
            var failure = cause instanceof MigrationException migrationFailure
                    ? migrationFailure
                    : new MigrationException(describeFailure(current), cause);
            // Enabling auto-commit commits any open transaction, so it is only restored after a successful rollback.
            if (rollback(connection, failure)) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }

        try {
            connection.setAutoCommit(true);
        } catch (SQLException failure) {
            throw new MigrationException("Could not restore auto-commit after migrations.", failure);
        }
        return List.copyOf(applied);
    }

    private List<Script> pending(List<HistoryEntry> history, List<Script> scripts) {
        var latest = scripts.getLast().migration().version();
        for (int index = 0; index < history.size(); index++) {
            var entry = history.get(index);
            if (entry.version() > latest) {
                throw new MigrationException("Database schema version V%03d is newer than the latest supported V%03d."
                        .formatted(entry.version(), latest));
            }

            var script = scripts.get(index);
            var expected = script.migration();
            if (entry.version() != expected.version()) {
                throw new MigrationException("Migration history is not a prefix of the catalog: found V%03d where V%03d was expected."
                        .formatted(entry.version(), expected.version()));
            }
            if (!expected.description().equals(entry.description())) {
                throw new MigrationException("Description recorded for V%03d differs from the catalog."
                        .formatted(entry.version()));
            }
            if (!script.resource().checksum().equals(entry.checksum())) {
                throw new MigrationException("Checksum recorded for V%03d differs from %s."
                        .formatted(entry.version(), expected.resource()));
            }
            requireInstant(entry);
        }
        return scripts.subList(history.size(), scripts.size());
    }

    private static void requireInstant(HistoryEntry entry) {
        try {
            SqliteInstantCodec.decode(entry.installedAt());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new MigrationException("installed_at recorded for V%03d is not a canonical instant."
                    .formatted(entry.version()), invalid);
        }
    }

    private static boolean historyTableExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'schema_version'");
             var result = statement.executeQuery()) {
            return result.next();
        }
    }

    private static List<HistoryEntry> readHistory(Connection connection) throws SQLException {
        var history = new ArrayList<HistoryEntry>();
        try (var statement = connection.prepareStatement(
                "SELECT version, description, checksum, installed_at FROM schema_version ORDER BY version");
             var result = statement.executeQuery()) {
            while (result.next()) {
                history.add(new HistoryEntry(
                        result.getLong("version"),
                        result.getString("description"),
                        result.getString("checksum"),
                        result.getString("installed_at")
                ));
            }
        }
        return history;
    }

    private static void execute(Connection connection, LoadedResource resource) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (var sql : resource.statements()) {
                statement.execute(sql);
            }
        }
    }

    private void record(Connection connection, Script script) throws SQLException {
        try (var insert = connection.prepareStatement(
                "INSERT INTO schema_version (version, description, checksum, installed_at) VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, script.migration().version());
            insert.setString(2, script.migration().description());
            insert.setString(3, script.resource().checksum());
            insert.setString(4, SqliteInstantCodec.encode(clock.now()));
            insert.executeUpdate();
        }
    }

    private static void requireReady(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                throw new MigrationException("Migrations require a connection without an open transaction.");
            }
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA foreign_keys")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new MigrationException("Migrations require foreign_keys enabled on the connection.");
                }
            }
        } catch (SQLException failure) {
            throw new MigrationException("Could not inspect the SQLite connection.", failure);
        }
    }

    private static boolean rollback(Connection connection, MigrationException failure) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private static String describeFailure(Migration current) {
        return current == null
                ? "Could not read the migration history; nothing was applied."
                : "Migration V%03d (%s) failed and was rolled back.".formatted(current.version(), current.description());
    }

    private static LoadedResource load(String resource) {
        byte[] bytes;
        try (var input = SqlMigrationRunner.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new MigrationException("Migration resource not found: " + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new MigrationException("Could not read migration resource: " + resource, failure);
        }

        return new LoadedResource(split(resource, decodeUtf8(resource, bytes)), sha256(bytes));
    }

    private static String decodeUtf8(String resource, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new MigrationException("Migration resource is not valid UTF-8: " + resource, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available in this JVM.", unavailable);
        }
    }

    private static List<String> split(String resource, String sql) {
        var statements = new ArrayList<String>();
        var segment = new StringBuilder();
        for (var line : sql.split("\\R", -1)) {
            if (line.strip().equals(STATEMENT_DELIMITER)) {
                statements.add(requireStatement(resource, segment.toString()));
                segment.setLength(0);
            } else {
                segment.append(line).append('\n');
            }
        }
        statements.add(requireStatement(resource, segment.toString()));
        return List.copyOf(statements);
    }

    private static String requireStatement(String resource, String segment) {
        var firstCodeLine = segment.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .findFirst()
                .orElseThrow(() -> new MigrationException("Migration resource has an empty statement segment: " + resource));

        var keyword = firstCodeLine.split("[^A-Za-z]", 2)[0].toUpperCase(Locale.ROOT);
        if (TRANSACTION_KEYWORDS.contains(keyword)) {
            throw new MigrationException("Migration resource must not control transactions (%s): %s"
                    .formatted(keyword, resource));
        }
        return segment;
    }

    private record LoadedResource(List<String> statements, String checksum) {
    }

    private record Script(Migration migration, LoadedResource resource) {
    }

    private record HistoryEntry(long version, String description, String checksum, String installedAt) {
    }
}
