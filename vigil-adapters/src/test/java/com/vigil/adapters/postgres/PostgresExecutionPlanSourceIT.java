package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.*;
import com.vigil.adapters.postgres.lab.PostgresLaboratory;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.core.domain.enums.PlanNodeType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresExecutionPlanSourceIT {
    @Container static final PostgreSQLContainer<?> DB = PostgresLaboratory.newContainer();
    private static final String READER = "vigil_plan_reader";
    private static final String PASSWORD = "test-" + java.util.UUID.randomUUID();

    @BeforeAll static void initialize() throws SQLException {
        PostgresLaboratory.initializeStandard(DB);
        try (Connection admin = admin(); var statement = admin.createStatement()) {
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
            statement.execute("GRANT SELECT ON orders TO " + READER);
            statement.execute("GRANT SELECT ON customers TO " + READER);
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }

    private static Connection reader() throws SQLException {
        Connection connection = DriverManager.getConnection(DB.getJdbcUrl() + "?ApplicationName=vigil", READER, PASSWORD);
        connection.setReadOnly(true);
        try (var statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '5s'");
            statement.execute("SET lock_timeout = '200ms'");
        }
        return connection;
    }

    private static PostgresExecutionPlanSource source() {
        return new PostgresExecutionPlanSource(() -> {
            try { return reader(); }
            catch (SQLException failure) { throw new IllegalStateException("Test connection failed", failure); }
        });
    }

    @Test void collectsLaboratoryPlans() {
        assertTrue(source().collect("SELECT id, status, total_cents FROM orders WHERE customer_id = 1").nodeTypes().contains(PlanNodeType.INDEX_SCAN));
        assertTrue(source().collect("SELECT id, total_cents FROM orders WHERE status = 'PENDING'").nodeTypes().contains(PlanNodeType.SEQUENTIAL_SCAN));
        assertFalse(source().collect("SELECT id, customer_id FROM orders ORDER BY placed_at DESC LIMIT 50").nodeTypes().isEmpty());
    }

    @Test void lockTimeoutRollsBackAndLeavesNoIdleTransaction() throws SQLException {
        try (Connection locker = admin()) {
            locker.setAutoCommit(false);
            try (var lock = locker.createStatement()) { lock.execute("LOCK TABLE orders IN ACCESS EXCLUSIVE MODE"); }
            long started = System.nanoTime();
            var failure = assertThrows(PlanCollectionRejected.class,
                    () -> source().collect("SELECT id, status, total_cents FROM orders WHERE customer_id = 1"));
            assertEquals(PlanRejectionReason.TIMEOUT, failure.reason());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 2);
            locker.rollback();
        }
        assertFalse(source().collect("SELECT id, status, total_cents FROM orders WHERE customer_id = 1").nodeTypes().isEmpty());
        try (Connection admin = admin(); var statement = admin.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'vigil' AND state = 'idle in transaction'")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }
}
