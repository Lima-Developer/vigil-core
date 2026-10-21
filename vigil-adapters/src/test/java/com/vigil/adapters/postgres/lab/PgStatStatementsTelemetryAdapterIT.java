package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.pgstatstatements.PgStatStatementsTelemetryAdapter;
import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.TelemetryCollection;
import com.vigil.application.preflight.CapabilityCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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

@Testcontainers
class PgStatStatementsTelemetryAdapterIT {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    @Container static final PostgreSQLContainer<?> LAB = PostgresLaboratory.newContainer();
    @Container static final PostgreSQLContainer<?> NO_EXTENSION = PostgresLaboratory.newContainer();
    @Container static final PostgreSQLContainer<?> NO_PRELOAD = new PostgreSQLContainer<>(PostgresLaboratory.IMAGE);
    @Container static final PostgreSQLContainer<?> NO_QUERY_ID =
            PostgresLaboratory.newContainerWithFlags("-c", "compute_query_id=off");

    private static final String PLAIN_ROLE = "vigil_plain";
    private static final String PLAIN_PASSWORD = "plain-" + UUID.randomUUID();

    @BeforeAll
    static void initialize() throws SQLException {
        PostgresLaboratory.initializeStandard(LAB);
        try (Connection connection = open(LAB, LAB.getUsername(), LAB.getPassword());
             Statement statement = connection.createStatement()) {
            // The password is a generated UUID, never external input.
            statement.execute("CREATE ROLE " + PLAIN_ROLE + " LOGIN PASSWORD '" + PLAIN_PASSWORD + "'");
            // Reading shared_preload_libraries needs pg_read_all_settings; pg_read_all_stats is deliberately withheld.
            statement.execute("GRANT pg_read_all_settings TO " + PLAIN_ROLE);
        }
        try (Connection connection = open(NO_PRELOAD, NO_PRELOAD.getUsername(), NO_PRELOAD.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
        try (Connection connection = open(NO_QUERY_ID, NO_QUERY_ID.getUsername(), NO_QUERY_ID.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
    }

    @Test
    void collectsTheLaboratoryWorkloadWithPredictableCounters() throws SQLException {
        TelemetryCollection collection = adapter(LAB, LAB.getUsername(), LAB.getPassword()).collect(NOW);

        assertEquals(NOW, collection.collectedAt());
        assertTrue(collection.fullVisibility());
        assertTrue(collection.statsReset().isPresent());
        assertEquals(0, collection.dealloc());
        assertEquals(0, collection.skippedRows());

        int pending = pendingOrders();
        assertEquals(750, LaboratoryDataset.HEALTHY_CUSTOMER_ORDERS * PostgresLaboratory.WORKLOAD_RUNS);
        assertCounters(collection, LaboratoryWorkload.H_SQL.replace("?", "$1"),
                (long) LaboratoryDataset.HEALTHY_CUSTOMER_ORDERS * PostgresLaboratory.WORKLOAD_RUNS);
        assertCounters(collection, LaboratoryWorkload.P1_SQL.replace("?", "$1"),
                (long) pending * PostgresLaboratory.WORKLOAD_RUNS);
        assertCounters(collection, LaboratoryWorkload.P2_SQL.replace("LIMIT 50", "LIMIT $1"), 50L * PostgresLaboratory.WORKLOAD_RUNS);
    }

    @Test
    void leavesOutTheCollectorAndPgStatStatementsQueriesAndCarriesNoBusinessData() throws SQLException {
        var adapter = adapter(LAB, LAB.getUsername(), LAB.getPassword());
        adapter.collect(NOW);
        TelemetryCollection collection = adapter.collect(NOW);

        Set<Long> returned = new HashSet<>();
        collection.queries().forEach(counters -> returned.add(counters.queryId()));
        List<Long> excluded = queryIdsWhereTextContains("pg_stat_statements");
        assertFalse(excluded.isEmpty(), "the collector must have left traces in the view");
        excluded.forEach(id -> assertFalse(returned.contains(id), "excluded query returned: " + id));
        assertFalse(queryIdsWhereTextContains("FROM pg_stat_statements WHERE dbid").stream().anyMatch(returned::contains));

        String rendered = collection.toString();
        assertFalse(rendered.contains("FROM orders"));
        assertFalse(rendered.contains("PENDING"));
    }

    @Test
    void connectionIsReadOnlyAndRejectsWrites() {
        AtomicBoolean readOnly = new AtomicBoolean();
        AtomicReference<SQLException> write = new AtomicReference<>();
        Supplier<Connection> supplier = () -> spy(LAB, readOnly, write);

        new PgStatStatementsTelemetryAdapter(supplier, new JdbcPostgresCapabilityProbe(plain(LAB))).collect(NOW);

        assertTrue(readOnly.get());
        assertNotNull(write.get(), "a write through the collector connection must fail");
        assertEquals("25006", write.get().getSQLState());
    }

    @Test
    void roleWithoutPgReadAllStatsIsFlaggedAsPartialVisibility() throws SQLException {
        TelemetryCollection collection = adapter(LAB, PLAIN_ROLE, PLAIN_PASSWORD).collect(NOW);

        assertFalse(collection.fullVisibility());
        Set<Long> returned = new HashSet<>();
        collection.queries().forEach(counters -> returned.add(counters.queryId()));
        assertFalse(returned.contains(queryId(LaboratoryWorkload.P2_SQL.replace("LIMIT 50", "LIMIT $1"))),
                "statements of other users are hidden, which is why visibility is flagged");
    }

    @Test
    void missingExtensionIsNamed() {
        assertMissing(NO_EXTENSION, CapabilityCode.PG_STAT_STATEMENTS_INSTALLED);
    }

    @Test
    void missingPreloadIsNamed() {
        assertMissing(NO_PRELOAD, CapabilityCode.PG_STAT_STATEMENTS_PRELOADED);
    }

    @Test
    void disabledQueryIdIsNamed() {
        assertMissing(NO_QUERY_ID, CapabilityCode.COMPUTE_QUERY_ID);
    }

    private static void assertMissing(PostgreSQLContainer<?> container, CapabilityCode expected) {
        var failure = assertThrows(TelemetryUnavailableException.class,
                () -> adapter(container, container.getUsername(), container.getPassword()).collect(NOW));
        assertEquals(expected, failure.missing());
        assertFalse(failure.getMessage().contains(container.getJdbcUrl()));
        assertFalse(failure.getMessage().contains(container.getPassword()));
    }

    private static void assertCounters(TelemetryCollection collection, String text, long expectedRows)
            throws SQLException {
        long queryId = queryId(text);
        List<CumulativeQueryCounters> matches = collection.queries().stream()
                .filter(counters -> counters.queryId() == queryId).toList();
        assertEquals(1, matches.size(), text);
        CumulativeQueryCounters counters = matches.getFirst();
        assertEquals(PostgresLaboratory.WORKLOAD_RUNS, counters.calls(), text);
        assertEquals(expectedRows, counters.rows(), text);
        assertTrue(counters.topLevel(), text);
        assertTrue(counters.totalExecutionMillis().signum() > 0, text);
        assertTrue(counters.sharedBlksHit() + counters.sharedBlksRead() > 0, text);
    }

    private static int pendingOrders() {
        int[] pending = {0};
        LaboratoryDataset.standard().forEachOrder(order -> {
            if (LaboratoryDataset.STATUS_PENDING.equals(order.status())) {
                pending[0]++;
            }
        });
        return pending[0];
    }

    private static long queryId(String text) throws SQLException {
        try (Connection connection = open(LAB, LAB.getUsername(), LAB.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT queryid FROM pg_stat_statements WHERE query = ?")) {
            statement.setString(1, text);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "statement not found: " + text);
                long id = result.getLong(1);
                assertFalse(result.next(), "statement is not unique: " + text);
                return id;
            }
        }
    }

    private static List<Long> queryIdsWhereTextContains(String fragment) throws SQLException {
        try (Connection connection = open(LAB, LAB.getUsername(), LAB.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT queryid FROM pg_stat_statements WHERE strpos(query, ?) > 0")) {
            statement.setString(1, fragment);
            try (var result = statement.executeQuery()) {
                var ids = new java.util.ArrayList<Long>();
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
                return ids;
            }
        }
    }

    private static PgStatStatementsTelemetryAdapter adapter(PostgreSQLContainer<?> container, String user,
                                                           String password) {
        Supplier<Connection> supplier = () -> unchecked(container, user, password);
        return new PgStatStatementsTelemetryAdapter(supplier, new JdbcPostgresCapabilityProbe(supplier));
    }

    private static Supplier<Connection> plain(PostgreSQLContainer<?> container) {
        return () -> unchecked(container, container.getUsername(), container.getPassword());
    }

    /** Wraps a real connection and, right before it is closed, records its read-only flag and a write attempt. */
    private static Connection spy(PostgreSQLContainer<?> container, AtomicBoolean readOnly,
                                  AtomicReference<SQLException> write) {
        Connection real = unchecked(container, container.getUsername(), container.getPassword());
        return (Connection) Proxy.newProxyInstance(PgStatStatementsTelemetryAdapterIT.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("close")) {
                            readOnly.set(real.isReadOnly());
                            try (Statement statement = real.createStatement()) {
                                statement.execute("CREATE TABLE vig034_write_probe (id int)");
                            } catch (SQLException expected) {
                                write.set(expected);
                            }
                        }
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Connection unchecked(PostgreSQLContainer<?> container, String user, String password) {
        try {
            return open(container, user, password);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection open(PostgreSQLContainer<?> container, String user, String password)
            throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), user, password);
    }
}
