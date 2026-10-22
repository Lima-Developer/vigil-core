package com.vigil.adapters.postgres.lab;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Lifecycle of the reproducible PostgreSQL laboratory. The container itself is owned by the caller (for example by
 * {@code @Container}); this class prepares it. Credentials always come from the container getters.
 */
public final class PostgresLaboratory {

    public static final String IMAGE = "postgres:16.4-bookworm";
    static final int WORKLOAD_RUNS = 50;

    private static final String SCHEMA_RESOURCE = "/lab/postgres/schema.sql";
    private static final String INSERT_CUSTOMER =
            "INSERT INTO customers (id, region, segment, created_at) VALUES (?, ?, ?, ?)";
    private static final String INSERT_ORDER =
            "INSERT INTO orders (id, customer_id, status, total_cents, placed_at) VALUES (?, ?, ?, ?, ?)";

    private final PostgreSQLContainer<?> container;
    private final LaboratoryDataset dataset;
    private final LaboratoryWorkload workload;

    PostgresLaboratory(PostgreSQLContainer<?> container, LaboratoryDataset dataset, LaboratoryWorkload workload) {
        this.container = container;
        this.dataset = dataset;
        this.workload = workload;
    }

    public static PostgreSQLContainer<?> newContainer() {
        return newContainer(IMAGE);
    }

    /** Same laboratory settings on another PostgreSQL image, for the version matrix of the integration tests. */
    public static PostgreSQLContainer<?> newContainer(String image) {
        return new PostgreSQLContainer<>(image).withCommand(
                "postgres",
                "-c", "shared_preload_libraries=pg_stat_statements",
                "-c", "pg_stat_statements.track=all");
    }

    /** Loads the standard dataset and workload into a started container, for tests outside this package. */
    public static void initializeStandard(PostgreSQLContainer<?> container) throws SQLException {
        new PostgresLaboratory(container, LaboratoryDataset.standard(), new LaboratoryWorkload()).initialize();
    }

    Connection connection() throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    void initialize() throws SQLException {
        try (Connection connection = connection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                statement.execute("SELECT pg_stat_statements_reset()");
                statement.execute(readSchema());
            }
            loadCustomers(connection);
            loadOrders(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE customers");
                statement.execute("ANALYZE orders");
            }
            workload.run(connection, WORKLOAD_RUNS);
        }
    }

    private void loadCustomers(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(INSERT_CUSTOMER)) {
            dataset.forEachCustomerBatch(batch -> insert(connection, statement, () -> {
                for (LaboratoryDataset.CustomerRow row : batch) {
                    statement.setInt(1, row.id());
                    statement.setString(2, row.region());
                    statement.setString(3, row.segment());
                    statement.setTimestamp(4, Timestamp.from(row.createdAt()));
                    statement.addBatch();
                }
            }));
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void loadOrders(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ORDER)) {
            dataset.forEachOrderBatch(batch -> insert(connection, statement, () -> {
                for (LaboratoryDataset.OrderRow row : batch) {
                    statement.setInt(1, row.id());
                    statement.setInt(2, row.customerId());
                    statement.setString(3, row.status());
                    statement.setInt(4, row.totalCents());
                    statement.setTimestamp(5, Timestamp.from(row.placedAt()));
                    statement.addBatch();
                }
            }));
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void insert(Connection connection, PreparedStatement statement, BatchFiller filler) {
        try {
            filler.fill();
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Laboratory batch load failed", e);
        }
    }

    private static String readSchema() {
        try (InputStream stream = PostgresLaboratory.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA_RESOURCE, e);
        }
    }

    @FunctionalInterface
    private interface BatchFiller {
        void fill() throws SQLException;
    }
}
