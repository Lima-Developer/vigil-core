package com.vigil.adapters.postgres.lab;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Known workload: one healthy query (H) and two problematic ones (P1, P2). Each query exists in a parameterised form,
 * used by {@link #run}, and in a literal form, used by {@code EXPLAIN} to avoid generic prepared-statement plans.
 */
final class LaboratoryWorkload {

    static final String H_SQL = "SELECT id, status, total_cents FROM orders WHERE customer_id = ?";
    static final String P1_SQL = "SELECT id, total_cents FROM orders WHERE status = ?";
    static final String P2_SQL = "SELECT id, customer_id FROM orders ORDER BY placed_at DESC LIMIT 50";

    static final String H_EXPLAIN_SQL =
            "SELECT id, status, total_cents FROM orders WHERE customer_id = " + LaboratoryDataset.HEALTHY_CUSTOMER_ID;
    static final String P1_EXPLAIN_SQL =
            "SELECT id, total_cents FROM orders WHERE status = '" + LaboratoryDataset.STATUS_PENDING + "'";
    static final String P2_EXPLAIN_SQL = P2_SQL;

    /**
     * Runs every query {@code times} times with fixed parameters, consuming each result set.
     *
     * @return total number of rows read
     */
    long run(Connection connection, int times) throws SQLException {
        long rows = 0;
        for (int i = 0; i < times; i++) {
            rows += consume(connection, H_SQL, LaboratoryDataset.HEALTHY_CUSTOMER_ID);
            rows += consume(connection, P1_SQL, LaboratoryDataset.STATUS_PENDING);
            rows += consume(connection, P2_SQL, null);
        }
        return rows;
    }

    private static long consume(Connection connection, String sql, Object parameter) throws SQLException {
        long rows = 0;
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
}
