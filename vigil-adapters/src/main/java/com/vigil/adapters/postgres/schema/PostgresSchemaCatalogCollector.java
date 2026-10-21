package com.vigil.adapters.postgres.schema;

import com.vigil.core.domain.SchemaBlueprint;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds a {@link SchemaBlueprint} of the requested relations from the PostgreSQL catalogs only. It never reads a user
 * table: no {@code COUNT(*)}, no rows, no DDL. The supplied connection is made read-only, runs one repeatable-read
 * transaction that is rolled back, and is closed after each collection.
 *
 * <p>A missing catalog privilege (SQLSTATE 42501) yields an explicitly incomplete blueprint; every other failure is a
 * {@link SchemaCollectionException} carrying only the SQLSTATE. Names are pseudonymized with a salt drawn per
 * collection from {@code saltSource}.
 */
public final class PostgresSchemaCatalogCollector {
    private static final String PRIVILEGE_DENIED = "42501";
    private static final int SALT_BYTES = 32;

    private final Supplier<Connection> connection;
    private final Supplier<byte[]> saltSource;

    public PostgresSchemaCatalogCollector(Supplier<Connection> connection) {
        this(connection, PostgresSchemaCatalogCollector::randomSalt);
    }

    public PostgresSchemaCatalogCollector(Supplier<Connection> connection, Supplier<byte[]> saltSource) {
        this.connection = Objects.requireNonNull(connection, "Connection supplier must not be null.");
        this.saltSource = Objects.requireNonNull(saltSource, "Salt source must not be null.");
    }

    public SchemaBlueprint collect(Instant collectedAt, Collection<RelationSelector> relevantRelations) {
        Objects.requireNonNull(collectedAt, "Collected at must not be null.");
        var selectors = new LinkedHashSet<>(Objects.requireNonNull(relevantRelations, "Relations must not be null."));
        if (selectors.isEmpty() || selectors.contains(null)) {
            throw new IllegalArgumentException("At least one relevant relation is required.");
        }
        var pseudonyms = new Pseudonymizer(saltSource.get());
        try (Connection opened = connection.get()) {
            // Read-only is enforced by the server only inside a transaction; repeatable read keeps the four
            // catalog queries on one snapshot.
            opened.setReadOnly(true);
            opened.setAutoCommit(false);
            opened.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var snapshot = CatalogReader.read(opened, List.copyOf(selectors));
            opened.rollback();
            return SchemaBlueprintAssembler.assemble(snapshot, pseudonyms, collectedAt, selectors.size());
        } catch (SQLException failure) {
            if (PRIVILEGE_DENIED.equals(failure.getSQLState())) {
                return SchemaBlueprintAssembler.privilegeDenied(collectedAt);
            }
            throw new SchemaCollectionException(failure.getSQLState());
        }
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }
}
