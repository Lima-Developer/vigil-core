package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.application.preflight.CapabilityCode;
import com.vigil.application.preflight.CapabilityStatus;
import com.vigil.application.preflight.CheckPostgresPreflight;
import com.vigil.application.preflight.PostgresPreflightReport;
import com.vigil.application.preflight.PostgresPreflightStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresPreflightIT {
    @Container static final PostgreSQLContainer<?> READY = PostgresLaboratory.newContainerWithFlags(
            "-c", "track_io_timing=on").withPassword("secret-" + UUID.randomUUID());
    @Container static final PostgreSQLContainer<?> DEFAULT = PostgresLaboratory.newContainer();
    @Container static final PostgreSQLContainer<?> NO_EXTENSION = PostgresLaboratory.newContainer();
    @Container static final PostgreSQLContainer<?> NO_PRELOAD = new PostgreSQLContainer<>(PostgresLaboratory.IMAGE);

    @BeforeAll
    static void initialize() throws SQLException {
        PostgresLaboratory.initializeStandard(READY);
        PostgresLaboratory.initializeStandard(DEFAULT);
    }

    @Test
    void fullLaboratoryIsReadyWithoutLeakingWorkloadOrSecrets() {
        var report = report(READY);
        assertEquals(PostgresPreflightStatus.READY, report.status(), report.toString());
        assertTrue(report.serverVersionNum() >= 160000);
        assertEquals(READY.getDatabaseName(), report.databaseName());
        assertEquals(CapabilityStatus.AVAILABLE, result(report, CapabilityCode.COMPUTE_QUERY_ID));
        String text = report.toString();
        assertFalse(text.contains("FROM orders"));
        assertFalse(text.contains("WHERE status"));
        assertFalse(text.contains(READY.getPassword()));
        assertFalse(text.contains(READY.getJdbcUrl()));
    }

    @Test
    void disabledTimingDegradesWithoutChangingTheSetting() throws SQLException {
        var report = report(DEFAULT);
        assertEquals(PostgresPreflightStatus.DEGRADED, report.status(), report.toString());
        assertEquals(CapabilityStatus.MISSING, result(report, CapabilityCode.TRACK_IO_TIMING));
        try (Connection connection = open(DEFAULT); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SHOW track_io_timing")) {
            assertTrue(result.next());
            assertEquals("off", result.getString(1));
        }
    }

    @Test
    void missingExtensionBlocks() {
        var report = report(NO_EXTENSION);
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertEquals(CapabilityStatus.AVAILABLE, result(report, CapabilityCode.PG_STAT_STATEMENTS_AVAILABLE), report.toString());
        assertEquals(CapabilityStatus.MISSING, result(report, CapabilityCode.PG_STAT_STATEMENTS_INSTALLED));
    }

    @Test
    void missingPreloadBlocksEvenWhenExtensionInstalled() throws SQLException {
        try (Connection connection = open(NO_PRELOAD); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
        var report = report(NO_PRELOAD);
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertEquals(CapabilityStatus.AVAILABLE, result(report, CapabilityCode.PG_STAT_STATEMENTS_INSTALLED));
        assertEquals(CapabilityStatus.MISSING, result(report, CapabilityCode.PG_STAT_STATEMENTS_PRELOADED));
    }

    @Test
    void invalidConnectionIsBlockedWithoutExposingUrl() {
        String wrongPassword = "wrong-" + UUID.randomUUID();
        var probe = new JdbcPostgresCapabilityProbe(() -> {
            try {
                return DriverManager.getConnection(READY.getJdbcUrl(), READY.getUsername(), wrongPassword);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        var report = new CheckPostgresPreflight(Instant::now, probe).check();
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertEquals(CapabilityCode.CONNECTION, report.capabilities().getFirst().code());
        assertFalse(report.toString().contains(wrongPassword));
        assertFalse(report.toString().contains(READY.getJdbcUrl()));
    }

    private static PostgresPreflightReport report(PostgreSQLContainer<?> container) {
        var probe = new JdbcPostgresCapabilityProbe(() -> {
            try {
                return open(container);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        return new CheckPostgresPreflight(Instant::now, probe).check();
    }

    private static Connection open(PostgreSQLContainer<?> container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static CapabilityStatus result(PostgresPreflightReport report, CapabilityCode code) {
        return report.capabilities().stream().filter(result -> result.code() == code)
                .findFirst().orElseThrow().status();
    }
}
