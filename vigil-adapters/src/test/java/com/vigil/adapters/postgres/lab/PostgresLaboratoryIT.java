package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresLaboratoryIT {

    @Container
    static final PostgreSQLContainer<?> CONTAINER = PostgresLaboratory.newContainer();

    private static final LaboratoryDataset DATASET = LaboratoryDataset.standard();
    private static final LaboratoryWorkload WORKLOAD = new LaboratoryWorkload();
    private static final PostgresLaboratory LABORATORY = new PostgresLaboratory(CONTAINER, DATASET, WORKLOAD);

    private static Connection connection;

    @BeforeAll
    static void initializeLaboratory() throws SQLException {
        LABORATORY.initialize();
        connection = LABORATORY.connection();
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void pgStatStatementsIsInstalledAndPreloaded() throws SQLException {
        assertTrue(strings("SELECT extname FROM pg_extension").contains("pg_stat_statements"));
        assertTrue(single("SHOW shared_preload_libraries").contains("pg_stat_statements"));
    }

    @Test
    void usesThePinnedImage() {
        assertEquals(PostgresLaboratory.IMAGE, CONTAINER.getDockerImageName());
        assertFalse(CONTAINER.getDockerImageName().contains("latest"));
    }

    @Test
    void loadedDataMatchesTheGenerator() throws SQLException {
        assertEquals(String.valueOf(DATASET.customerCount()), single("SELECT count(*) FROM customers"));
        assertEquals(String.valueOf(DATASET.orderCount()), single("SELECT count(*) FROM orders"));
        assertEquals(String.valueOf(DATASET.sumTotalCents()), single("SELECT sum(total_cents) FROM orders"));
    }

    @Test
    void onlyPrimaryKeysAndCustomerIndexExist() throws SQLException {
        assertEquals(List.of("customers_pkey", "orders_customer_id_idx", "orders_pkey"),
                strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname"));
    }

    @Test
    void workloadIsRecordedByNormalizedText() throws SQLException {
        assertCalls("SELECT id, status, total_cents FROM orders WHERE customer_id = $1");
        assertCalls("SELECT id, total_cents FROM orders WHERE status = $1");
        assertCalls("SELECT id, customer_id FROM orders ORDER BY placed_at DESC LIMIT $1");
    }

    @Test
    void healthyQueryUsesTheCustomerIndex() throws SQLException {
        String plan = explain(LaboratoryWorkload.H_EXPLAIN_SQL);

        assertTrue(plan.contains("orders_customer_id_idx"), plan);
        assertFalse(plan.contains("Seq Scan on orders"), plan);
    }

    @Test
    void statusQueryScansTheWholeTable() throws SQLException {
        String plan = explain(LaboratoryWorkload.P1_EXPLAIN_SQL);

        assertTrue(plan.contains("Seq Scan on orders"), plan);
    }

    @Test
    void recentOrdersQuerySortsAfterASequentialScan() throws SQLException {
        String plan = explain(LaboratoryWorkload.P2_EXPLAIN_SQL);

        assertTrue(plan.contains("Sort"), plan);
        assertTrue(plan.contains("Seq Scan on orders"), plan);
    }

    private static void assertCalls(String normalizedQuery) throws SQLException {
        long calls = calls(normalizedQuery);
        assertTrue(calls >= PostgresLaboratory.WORKLOAD_RUNS, normalizedQuery + " calls=" + calls);
    }

    private static long calls(String normalizedQuery) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT coalesce(sum(calls), 0) FROM pg_stat_statements WHERE query = ?")) {
            statement.setString(1, normalizedQuery);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static String explain(String sql) throws SQLException {
        return String.join("\n", strings("EXPLAIN " + sql));
    }

    private static String single(String sql) throws SQLException {
        return strings(sql).getFirst();
    }

    private static List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
