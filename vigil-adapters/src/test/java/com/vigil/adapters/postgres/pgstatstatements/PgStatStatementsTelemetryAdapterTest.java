package com.vigil.adapters.postgres.pgstatstatements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.exception.TelemetryUnavailableException;
import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import com.vigil.application.preflight.CapabilityCode;
import com.vigil.application.preflight.PreflightProbeException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PgStatStatementsTelemetryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    private final AtomicInteger opened = new AtomicInteger();

    private PgStatStatementsTelemetryAdapter adapter(PostgresPreflightFacts facts) {
        return new PgStatStatementsTelemetryAdapter(() -> {
            opened.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        throw new AssertionError("The view must not be touched: " + method.getName());
                    });
        }, () -> facts);
    }

    private static PostgresPreflightFacts facts(int version, boolean installed, boolean preloaded, String queryId) {
        return new PostgresPreflightFacts(version, "db", true, installed, preloaded, true, queryId, "on");
    }

    @ParameterizedTest
    @ValueSource(ints = {139999, 90600, 0})
    void versionsBelow14AreUnavailable(int version) {
        assertMissing(CapabilityCode.SERVER_VERSION, facts(version, true, true, "on"));
    }

    @Test
    void extensionNotInstalledIsUnavailable() {
        assertMissing(CapabilityCode.PG_STAT_STATEMENTS_INSTALLED, facts(160004, false, true, "on"));
    }

    @Test
    void libraryNotPreloadedIsUnavailable() {
        assertMissing(CapabilityCode.PG_STAT_STATEMENTS_PRELOADED, facts(160004, true, false, "on"));
    }

    @ParameterizedTest
    @CsvSource(value = {"off", "regress", "NULL"}, nullValues = "NULL")
    void queryIdNotComputedIsUnavailable(String queryId) {
        assertMissing(CapabilityCode.COMPUTE_QUERY_ID, facts(160004, true, true, queryId));
    }

    @Test
    void firstMissingCapabilityWinsInDocumentedOrder() {
        assertMissing(CapabilityCode.SERVER_VERSION, facts(139999, false, false, "off"));
        assertMissing(CapabilityCode.PG_STAT_STATEMENTS_INSTALLED, facts(160004, false, false, "off"));
        assertMissing(CapabilityCode.PG_STAT_STATEMENTS_PRELOADED, facts(160004, true, false, "off"));
    }

    @ParameterizedTest
    @ValueSource(ints = {140000, 140001, 160004})
    void supportedVersionsPassTheCapabilityCheck(int version) {
        assertTrue(PgStatStatementsCapabilities.firstMissing(facts(version, true, true, "on")).isEmpty());
        assertTrue(PgStatStatementsCapabilities.firstMissing(facts(version, true, true, "AUTO")).isEmpty());
    }

    @Test
    void connectionIsOpenedOnlyAfterCapabilitiesPass() {
        // 140000 passes the capability check, so the connection is requested and the (failing) fake is reached.
        var adapter = adapter(facts(140000, true, true, "on"));
        assertThrows(AssertionError.class, () -> adapter.collect(NOW));
        assertEquals(1, opened.get());
    }

    @Test
    void probeFailureIsNotSwallowed() {
        var failure = new PreflightProbeException(CapabilityCode.CONNECTION);
        var adapter = new PgStatStatementsTelemetryAdapter(() -> {
            throw new AssertionError("must not open");
        }, () -> {
            throw failure;
        });
        assertSame(failure, assertThrows(PreflightProbeException.class, () -> adapter.collect(NOW)));
    }

    @Test
    void collectionSqlNeverProjectsTheStatementText() {
        String sql = PgStatStatementsTelemetryAdapter.COLLECTION_SQL;
        String projection = sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
        assertFalse(Pattern.compile("\\bquery\\b").matcher(projection).find(), projection);
        assertFalse(projection.contains("*"));
        assertTrue(sql.contains("dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"));
        assertTrue(sql.contains("query IS NOT NULL"));
        assertTrue(sql.contains("ltrim(query) ~* '^(select|with)\\y'"));
        assertTrue(sql.contains("query NOT ILIKE '%pg_stat_statements%'"));
        assertFalse(sql.toLowerCase().contains("reset("));
        assertFalse(PgStatStatementsTelemetryAdapter.INFO_SQL.toLowerCase().contains("reset("));
    }

    private void assertMissing(CapabilityCode expected, PostgresPreflightFacts facts) {
        var failure = assertThrows(TelemetryUnavailableException.class, () -> adapter(facts).collect(NOW));
        assertEquals(expected, failure.missing());
        assertTrue(failure.getMessage().contains(expected.name()));
        assertEquals(0, opened.get());
    }
}
