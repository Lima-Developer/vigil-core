package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.schema.PostgresSchemaCatalogCollector;
import com.vigil.adapters.postgres.schema.RelationSelector;
import com.vigil.core.domain.ColumnBlueprint;
import com.vigil.core.domain.ConstraintBlueprint;
import com.vigil.core.domain.IndexBlueprint;
import com.vigil.core.domain.RelationBlueprint;
import com.vigil.core.domain.SchemaBlueprint;
import com.vigil.core.domain.enums.ConstraintKind;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.RelationType;
import com.vigil.core.domain.enums.SchemaIncompleteness;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Version matrix: run with {@code -Dvigil.postgres.image=postgres:14} (or 15, 17) to repeat the suite. */
@Testcontainers
class PostgresSchemaCatalogCollectorIT {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final String SCHEMA = "vig035_fixture";
    private static final String READER = "vigil_schema_reader";
    private static final String READER_PASSWORD = "reader-" + UUID.randomUUID();
    private static final String FIXTURE = "/postgres/schema-catalog-fixture.sql";
    private static final List<String> ORIGINALS = List.of("vig035", "fixture", "secret", "accounts", "owner_cpf",
            "email", "balance", "tier", "events", "ledger", "remote", "1234567", "000.111", "ana@", "vip");

    @Container
    static final PostgreSQLContainer<?> LAB =
            new PostgreSQLContainer<>(System.getProperty("vigil.postgres.image", PostgresLaboratory.IMAGE));

    @BeforeAll
    static void loadFixtureAndLeastPrivilegeRole() throws SQLException {
        try (Connection connection = open(LAB.getUsername(), LAB.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(fixture());
            // The password is a generated UUID, never external input. The role gets no grant on any table or schema.
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
        }
    }

    @Test
    void tableCarriesTypesNullabilityConstraintsAndEveryIndexOnce() {
        SchemaBlueprint blueprint = collect(READER, READER_PASSWORD, "accounts_secret");
        RelationBlueprint table = single(blueprint);

        assertTrue(blueprint.complete());
        assertEquals(NOW, blueprint.collectedAt());
        assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        assertEquals(RelationType.TABLE, table.type());
        assertEquals(OptionalLong.of(3), table.estimatedRows());
        assertTrue(table.pages() >= 1);

        assertEquals(List.of("bigint", "text", "character varying(120)", "numeric(12,2)", "user_defined", "text"),
                table.columns().stream().map(ColumnBlueprint::sqlType).toList());
        assertEquals(List.of(false, false, true, true, true, true),
                table.columns().stream().map(ColumnBlueprint::nullable).toList());

        Set<ConstraintKind> kinds = new HashSet<>();
        table.constraints().stream().map(ConstraintBlueprint::kind).forEach(kinds::add);
        assertEquals(Set.of(ConstraintKind.PRIMARY_KEY, ConstraintKind.UNIQUE, ConstraintKind.CHECK), kinds);
        assertEquals(3, table.constraints().size());
        var primaryKey = table.constraints().stream().filter(c -> c.kind() == ConstraintKind.PRIMARY_KEY).findFirst()
                .orElseThrow();
        assertEquals(List.of(table.columns().get(0).column()), primaryKey.columns());

        assertEquals(4, table.indexes().size());
        assertEquals(4, table.indexes().stream().map(IndexBlueprint::index).distinct().count());
        assertEquals(1, table.indexes().stream().filter(IndexBlueprint::primary).count());
        assertEquals(1, table.indexes().stream().filter(IndexBlueprint::partial).count());
        assertTrue(table.indexes().stream().allMatch(index -> index.valid() && index.accessMethod().equals("btree")));
        var partial = table.indexes().stream().filter(IndexBlueprint::partial).findFirst().orElseThrow();
        assertEquals(1, partial.keys().size());
        assertEquals(table.columns().get(1).column(), partial.keys().getFirst().column().orElseThrow());
        var expression = table.indexes().stream().filter(index -> index.keys().getFirst().isExpression())
                .findFirst().orElseThrow();
        assertEquals(2, expression.keys().size());
        assertEquals(table.columns().get(0).column(), expression.keys().get(1).column().orElseThrow());
    }

    @Test
    void materializedViewIsARelationOfItsOwnType() {
        RelationBlueprint view = single(collect(READER, READER_PASSWORD, "account_totals_secret"));

        assertEquals(RelationType.MATERIALIZED_VIEW, view.type());
        assertEquals(OptionalLong.of(3), view.estimatedRows());
        assertEquals(List.of("text", "numeric"), view.columns().stream().map(ColumnBlueprint::sqlType).toList());
        assertEquals(1, view.indexes().size());
        assertTrue(view.indexes().getFirst().unique());
        assertFalse(view.indexes().getFirst().partial());
    }

    @Test
    void partitionedTableAndItsPartitionEachKeepTheirOwnIndexOnce() {
        SchemaBlueprint blueprint = collect(READER, READER_PASSWORD, "events_secret", "events_secret_2026");

        assertEquals(2, blueprint.relations().size());
        var types = blueprint.relations().stream().map(RelationBlueprint::type).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(RelationType.PARTITIONED_TABLE, RelationType.TABLE), types);
        blueprint.relations().forEach(relation -> assertEquals(1, relation.indexes().size()));
        var references = blueprint.relations().stream().flatMap(relation -> relation.indexes().stream())
                .map(IndexBlueprint::index).distinct().count();
        assertEquals(2, references, "inherited partition index is a distinct catalog entry, listed once per relation");
        var parent = blueprint.relations().stream().filter(r -> r.type() == RelationType.PARTITIONED_TABLE)
                .findFirst().orElseThrow();
        assertEquals(List.of("bigint", "integer", "timestamp with time zone", "jsonb"),
                parent.columns().stream().map(ColumnBlueprint::sqlType).toList());
    }

    @Test
    void foreignTableIsDescribedWithoutBeingRead() {
        RelationBlueprint foreign = single(collect(READER, READER_PASSWORD, "remote_ledger_secret"));

        assertEquals(RelationType.FOREIGN_TABLE, foreign.type());
        assertEquals(List.of("integer", "numeric(10,2)"), foreign.columns().stream().map(ColumnBlueprint::sqlType).toList());
        assertTrue(foreign.indexes().isEmpty());
    }

    @Test
    void neverAnalyzedRelationHasUnknownEstimateNotZero() {
        RelationBlueprint fresh = single(collect(READER, READER_PASSWORD, "fresh_secret"));

        assertEquals(OptionalLong.empty(), fresh.estimatedRows());
        assertEquals(0, fresh.pages());
    }

    @Test
    void collectsWithARoleThatCannotReadAnyBusinessTable() throws SQLException {
        try (Connection connection = open(READER, READER_PASSWORD);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT has_table_privilege(c.oid, 'SELECT')"
                     + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                     + " WHERE n.nspname = 'vig035_fixture' AND c.relname = 'accounts_secret'")) {
            assertTrue(result.next());
            assertFalse(result.getBoolean(1), "the collector role must hold no SELECT on the fixture");
        }
        assertEquals(1, single(collect(READER, READER_PASSWORD, "accounts_secret")).constraints().stream()
                .filter(c -> c.kind() == ConstraintKind.PRIMARY_KEY).count());
    }

    @Test
    void blueprintCarriesNoOriginalNameLiteralOrValue() {
        SchemaBlueprint blueprint = collect(READER, READER_PASSWORD, "accounts_secret", "account_totals_secret",
                "events_secret", "events_secret_2026", "remote_ledger_secret", "fresh_secret");

        assertEquals(6, blueprint.relations().size());
        String rendered = blueprint.toString().toLowerCase();
        for (String original : ORIGINALS) {
            assertFalse(rendered.contains(original), original);
        }
    }

    @Test
    void pseudonymsAreStableWithinOneSaltAndChangeWithAnother() {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 7);
        var first = collector(READER, READER_PASSWORD, () -> salt).collect(NOW, selectors("accounts_secret"));
        var second = collector(READER, READER_PASSWORD, () -> salt).collect(NOW, selectors("accounts_secret"));
        var other = collect(READER, READER_PASSWORD, "accounts_secret");

        assertEquals(first, second);
        assertNotEquals(single(first).relation(), single(other).relation());
        assertNotEquals(single(first).columns().getFirst().column(), single(other).columns().getFirst().column());
    }

    @Test
    void relationsThatAreNotRequestedOrNotSupportedAreLeftOut() {
        SchemaBlueprint blueprint = collect(READER, READER_PASSWORD, "accounts_secret", "plain_view_secret",
                "does_not_exist");

        assertEquals(1, blueprint.relations().size());
        assertEquals(RelationType.TABLE, blueprint.relations().getFirst().type());
        assertEquals(Set.of(SchemaIncompleteness.RELATION_NOT_VISIBLE), blueprint.incompleteness());
    }

    @Test
    void missingCatalogPrivilegeIsExplicitIncompletenessNotAFailure() throws SQLException {
        execute("REVOKE SELECT ON pg_catalog.pg_index FROM PUBLIC");
        try {
            SchemaBlueprint blueprint = collect(READER, READER_PASSWORD, "accounts_secret");

            assertEquals(Set.of(SchemaIncompleteness.CATALOG_PRIVILEGE_DENIED), blueprint.incompleteness());
            assertTrue(blueprint.relations().isEmpty());
            assertEquals(NOW, blueprint.collectedAt());
            assertEquals(EvidenceSource.POSTGRESQL_CATALOG, blueprint.source());
        } finally {
            execute("GRANT SELECT ON pg_catalog.pg_index TO PUBLIC");
        }
        assertTrue(collect(READER, READER_PASSWORD, "accounts_secret").complete());
    }

    @Test
    void connectionIsReadOnlyAndRejectsWrites() {
        AtomicBoolean readOnly = new AtomicBoolean();
        AtomicReference<SQLException> write = new AtomicReference<>();
        Supplier<Connection> supplier = () -> spy(readOnly, write);

        new PostgresSchemaCatalogCollector(supplier).collect(NOW, selectors("accounts_secret"));

        assertTrue(readOnly.get());
        assertNotNull(write.get(), "a write through the collector connection must fail");
        assertEquals("25006", write.get().getSQLState());
    }

    private static SchemaBlueprint collect(String user, String password, String... relations) {
        return collector(user, password, null).collect(NOW, selectors(relations));
    }

    private static PostgresSchemaCatalogCollector collector(String user, String password, Supplier<byte[]> salt) {
        Supplier<Connection> supplier = () -> unchecked(user, password);
        return salt == null ? new PostgresSchemaCatalogCollector(supplier)
                : new PostgresSchemaCatalogCollector(supplier, salt);
    }

    private static List<RelationSelector> selectors(String... relations) {
        return Arrays.stream(relations).map(name -> new RelationSelector(SCHEMA, name)).toList();
    }

    private static RelationBlueprint single(SchemaBlueprint blueprint) {
        assertEquals(1, blueprint.relations().size());
        return blueprint.relations().getFirst();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open(LAB.getUsername(), LAB.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Wraps a real connection and, right before it is closed, records its read-only flag and a write attempt. */
    private static Connection spy(AtomicBoolean readOnly, AtomicReference<SQLException> write) {
        Connection real = unchecked(READER, READER_PASSWORD);
        return (Connection) Proxy.newProxyInstance(PostgresSchemaCatalogCollectorIT.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("close")) {
                            readOnly.set(real.isReadOnly());
                            try (Statement statement = real.createStatement()) {
                                statement.execute("CREATE TABLE vig035_write_probe (id int)");
                            } catch (SQLException expected) {
                                write.set(expected);
                            }
                        }
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static String fixture() {
        try (InputStream stream = PostgresSchemaCatalogCollectorIT.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource " + FIXTURE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + FIXTURE, failure);
        }
    }

    private static Connection unchecked(String user, String password) {
        try {
            return open(user, password);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection open(String user, String password) throws SQLException {
        return DriverManager.getConnection(LAB.getJdbcUrl(), user, password);
    }
}
