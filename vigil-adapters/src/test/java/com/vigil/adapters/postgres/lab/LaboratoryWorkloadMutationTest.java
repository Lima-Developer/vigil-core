package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Checks each workload query on its own, so that swapped SQL texts or swapped parameters are detected per query, not only
 * through the total of {@link LaboratoryWorkload#run}. Runs on in-memory SQLite, like {@link LaboratoryWorkloadTest}.
 */
class LaboratoryWorkloadMutationTest {

    private static final LaboratoryDataset DATASET = new LaboratoryDataset(42, 100, 2_000);

    @Test
    void healthyQueryReturnsExactlyTheHealthyCustomerOrders() throws SQLException {
        try (Connection connection = memory()) {
            load(connection);

            assertEquals(LaboratoryDataset.HEALTHY_CUSTOMER_ORDERS,
                    rows(connection, LaboratoryWorkload.H_SQL, LaboratoryDataset.HEALTHY_CUSTOMER_ID));
        }
    }

    @Test
    void statusQueryReturnsExactlyThePendingOrders() throws SQLException {
        try (Connection connection = memory()) {
            load(connection);

            assertEquals(pendingOrders(), rows(connection, LaboratoryWorkload.P1_SQL, LaboratoryDataset.STATUS_PENDING));
        }
    }

    @Test
    void recentOrdersQueryReturnsFiftyRows() throws SQLException {
        try (Connection connection = memory()) {
            load(connection);

            assertEquals(50, rows(connection, LaboratoryWorkload.P2_SQL, null));
        }
    }

    @Test
    void healthyQueryDoesNotAcceptTheStatusParameter() throws SQLException {
        try (Connection connection = memory()) {
            load(connection);

            assertEquals(0, rows(connection, LaboratoryWorkload.H_SQL, LaboratoryDataset.STATUS_PENDING));
        }
    }

    private long pendingOrders() {
        long[] pending = {0};
        DATASET.forEachOrder(order -> {
            if (order.status().equals(LaboratoryDataset.STATUS_PENDING)) {
                pending[0]++;
            }
        });
        return pending[0];
    }

    private static int rows(Connection connection, String sql, Object parameter) throws SQLException {
        int rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows++;
                }
            }
        }
        return rows;
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
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
