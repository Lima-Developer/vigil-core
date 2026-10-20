package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the workload against in-memory SQLite, which accepts the same portable SQL, to pin the execution contract. */
class LaboratoryWorkloadTest {

    private static final LaboratoryDataset DATASET = new LaboratoryDataset(42, 100, 2_000);

    @Test
    void runsEachQueryTheRequestedNumberOfTimes() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            load(connection);
            List<LaboratoryDataset.OrderRow> orders = new ArrayList<>();
            DATASET.forEachOrder(orders::add);
            long healthy = orders.stream().filter(o -> o.customerId() == LaboratoryDataset.HEALTHY_CUSTOMER_ID).count();
            long pending = orders.stream().filter(o -> o.status().equals(LaboratoryDataset.STATUS_PENDING)).count();
            long perRound = healthy + pending + 50;

            assertEquals(LaboratoryDataset.HEALTHY_CUSTOMER_ORDERS, healthy);
            assertEquals(3 * perRound, new LaboratoryWorkload().run(connection, 3));
            assertEquals(0, new LaboratoryWorkload().run(connection, 0));
        }
    }

    @Test
    void explainFormsUseLiteralsInsteadOfPlaceholders() {
        assertEquals("SELECT id, status, total_cents FROM orders WHERE customer_id = 1", LaboratoryWorkload.H_EXPLAIN_SQL);
        assertEquals("SELECT id, total_cents FROM orders WHERE status = 'PENDING'", LaboratoryWorkload.P1_EXPLAIN_SQL);
        assertEquals("SELECT id, customer_id FROM orders ORDER BY placed_at DESC LIMIT 50", LaboratoryWorkload.P2_EXPLAIN_SQL);
        assertTrue(LaboratoryWorkload.H_SQL.endsWith("customer_id = ?"));
        assertTrue(LaboratoryWorkload.P1_SQL.endsWith("status = ?"));
    }

    private static void load(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, status TEXT,"
                    + " total_cents INTEGER, placed_at TEXT)");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO orders VALUES (?, ?, ?, ?, ?)")) {
            DATASET.forEachOrder(order -> {
                try {
                    insert.setInt(1, order.id());
                    insert.setInt(2, order.customerId());
                    insert.setString(3, order.status());
                    insert.setInt(4, order.totalCents());
                    insert.setString(5, order.placedAt().toString());
                    insert.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
