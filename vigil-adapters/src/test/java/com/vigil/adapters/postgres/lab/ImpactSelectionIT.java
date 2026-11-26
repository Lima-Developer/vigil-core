package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.fingerprint.HmacQueryFingerprinter;
import com.vigil.adapters.postgres.pgstatstatements.PgStatStatementsImpactAdapter;
import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.application.casework.SelectCandidatesByImpactUseCase;
import com.vigil.application.port.out.interfaces.ImpactTelemetrySnapshot;
import com.vigil.application.telemetry.QueryTelemetryDeltaCalculator;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.ImpactSelectionPolicy;
import com.vigil.core.domain.ImpactSelectionResult;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end evidence of "selection by impact": a heavy query and a cheap, high-call query run against a real
 * PostgreSQL 16 with pg_stat_statements; between two snapshots the heavy family is selected and the cheap one is not,
 * even though the cheap query has far more calls (criterion 6).
 */
@Testcontainers
class ImpactSelectionIT {
    private static final Instant T1 = Instant.parse("2026-11-24T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-24T12:05:00Z");
    private static final String HEAVY_SQL = "SELECT count(*) FROM generate_series(1, 4000000)";
    private static final String CHEAP_SQL = "SELECT 1";
    private static final int HEAVY_RUNS = 8;
    private static final int CHEAP_RUNS = 3000;

    @Container static final PostgreSQLContainer<?> LAB = PostgresLaboratory.newContainer();

    private final SelectCandidatesByImpactUseCase useCase = new SelectCandidatesByImpactUseCase(
            new QueryTelemetryDeltaCalculator(), new ImpactSelectionPolicy());

    @BeforeAll
    static void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        }
    }

    @Test
    void selectsTheHeavyQueryAndNotTheCheapHighCallQuery() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_statements_reset()");
        }
        runWorkload();
        ImpactTelemetrySnapshot before = adapter().collectForImpactSelection(T1);
        runWorkload();
        ImpactTelemetrySnapshot after = adapter().collectForImpactSelection(T2);

        ImpactSelectionResult result = useCase.execute(before, after);

        long heavyId = queryIdLike("%generate_series%");
        long cheapId = queryIdExact("SELECT $1");
        Candidate top = result.candidates().get(0);

        assertAll(
                () -> assertEquals(ImpactSelectionResult.Outcome.SELECTED, result.outcome()),
                () -> assertTrue(top.sourceQueryIds().stream().anyMatch(id -> id.value().equals(Long.toString(heavyId))),
                        "the heavy query must be selected"),
                () -> assertTrue(result.candidates().stream().flatMap(c -> c.sourceQueryIds().stream())
                                .noneMatch(id -> id.value().equals(Long.toString(cheapId))),
                        "the cheap high-call query must not be selected (criterion 6)"),
                () -> assertTrue(top.assessment().delta().workspaceShare().doubleValue() > 0.3,
                        "share=" + top.assessment().delta().workspaceShare()
                                + " reason=" + top.selectionReason().render()));

        // Evidence for the report: which query was selected and with what share.
        System.out.println("[ImpactSelectionIT] selected heavy queryid=" + heavyId
                + "; cheap queryid=" + cheapId + " excluded; " + top.selectionReason().render());
    }

    private void runWorkload() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (int i = 0; i < HEAVY_RUNS; i++) {
                statement.executeQuery(HEAVY_SQL).close();
            }
            for (int i = 0; i < CHEAP_RUNS; i++) {
                statement.executeQuery(CHEAP_SQL).close();
            }
        }
    }

    private PgStatStatementsImpactAdapter adapter() {
        Supplier<Connection> supplier = ImpactSelectionIT::unchecked;
        return new PgStatStatementsImpactAdapter(supplier, new JdbcPostgresCapabilityProbe(supplier),
                new HmacQueryFingerprinter("impact-it-key".getBytes(StandardCharsets.UTF_8)));
    }

    private static long queryIdLike(String fragment) throws SQLException {
        return lookup("SELECT queryid FROM pg_stat_statements WHERE query LIKE ?"
                + " AND query NOT ILIKE '%pg_stat_statements%'", fragment);
    }

    private static long queryIdExact(String text) throws SQLException {
        return lookup("SELECT queryid FROM pg_stat_statements WHERE query = ?", text);
    }

    private static long lookup(String sql, String parameter) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "statement not found: " + parameter);
                long id = result.getLong(1);
                assertFalse(result.next(), "statement not unique: " + parameter);
                return id;
            }
        }
    }

    private static Connection unchecked() {
        try {
            return open();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(LAB.getJdbcUrl(), LAB.getUsername(), LAB.getPassword());
    }
}
