package com.vigil.application.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CheckPostgresPreflightTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private PostgresPreflightFacts facts(int version, boolean available, boolean installed, boolean preload,
                                        boolean readable, String queryId, String io) {
        return new PostgresPreflightFacts(version, "vigil", available, installed, preload, readable, queryId, io);
    }

    private PostgresPreflightReport check(PostgresPreflightFacts facts) {
        return new CheckPostgresPreflight(() -> NOW, () -> facts).check();
    }

    private PostgresPreflightFacts complete() {
        return facts(140000, true, true, true, true, "auto", "on");
    }

    @Test
    void completeEnvironmentIsReadyAtMinimumVersionAndOrdered() {
        var report = check(complete());
        assertEquals(PostgresPreflightStatus.READY, report.status());
        assertEquals(NOW, report.checkedAt());
        assertEquals(140000, report.serverVersionNum());
        assertEquals("vigil", report.databaseName());
        assertEquals(List.of(CapabilityCode.CONNECTION, CapabilityCode.SERVER_VERSION,
                CapabilityCode.PG_STAT_STATEMENTS_AVAILABLE, CapabilityCode.PG_STAT_STATEMENTS_INSTALLED,
                CapabilityCode.PG_STAT_STATEMENTS_PRELOADED, CapabilityCode.STATS_READABLE,
                CapabilityCode.COMPUTE_QUERY_ID, CapabilityCode.TRACK_IO_TIMING),
                report.capabilities().stream().map(CapabilityResult::code).toList());
    }

    @ParameterizedTest
    @CsvSource({"139999,true,true,true,true,on,on,SERVER_VERSION",
            "140000,false,true,true,true,on,on,PG_STAT_STATEMENTS_AVAILABLE",
            "140000,true,false,true,true,on,on,PG_STAT_STATEMENTS_INSTALLED",
            "140000,true,true,false,true,on,on,PG_STAT_STATEMENTS_PRELOADED",
            "140000,true,true,true,false,on,on,STATS_READABLE",
            "140000,true,true,true,true,off,on,COMPUTE_QUERY_ID",
            "140000,true,true,true,true,unknown,on,COMPUTE_QUERY_ID"})
    void missingRequiredCapabilityBlocks(int version, boolean available, boolean installed, boolean preload,
                                         boolean readable, String queryId, String io, CapabilityCode missing) {
        var report = check(facts(version, available, installed, preload, readable, queryId, io));
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertEquals(CapabilityStatus.MISSING, result(report, missing).status());
        assertFalse(result(report, missing).remediation().isBlank());
    }

    @ParameterizedTest
    @CsvSource({"on,READY", "auto,READY", "off,BLOCKED", "unknown,BLOCKED"})
    void queryIdModes(String mode, PostgresPreflightStatus expected) {
        assertEquals(expected, check(facts(140000, true, true, true, true, mode, "on")).status());
    }

    @Test
    void unknownQueryIdBlocks() {
        var report = check(facts(140000, true, true, true, true, null, "on"));
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertEquals(CapabilityStatus.UNKNOWN, result(report, CapabilityCode.COMPUTE_QUERY_ID).status());
    }

    @ParameterizedTest
    @CsvSource({"on,READY,AVAILABLE", "off,DEGRADED,MISSING"})
    void timingModes(String io, PostgresPreflightStatus expected, CapabilityStatus availability) {
        var report = check(facts(140000, true, true, true, true, "on", io));
        assertEquals(expected, report.status());
        assertEquals(availability, result(report, CapabilityCode.TRACK_IO_TIMING).status());
    }

    @Test
    void unknownTimingDegradesAndRequiredFailureTakesPrecedence() {
        var unknown = check(facts(140000, true, true, true, true, "on", null));
        assertEquals(PostgresPreflightStatus.DEGRADED, unknown.status());
        assertEquals(CapabilityStatus.UNKNOWN, result(unknown, CapabilityCode.TRACK_IO_TIMING).status());
        assertEquals(PostgresPreflightStatus.BLOCKED,
                check(facts(139999, true, true, true, true, "on", "off")).status());
    }

    @Test
    void autoRequiresPreload() {
        var report = check(facts(140000, true, true, false, true, "auto", "on"));
        assertEquals(CapabilityStatus.MISSING, result(report, CapabilityCode.COMPUTE_QUERY_ID).status());
    }

    @Test
    void probeFailureBecomesSanitizedBlockedReport() {
        var report = new CheckPostgresPreflight(() -> NOW, () -> {
            throw new IllegalStateException("password=secret jdbc:postgresql://private");
        }).check();
        assertEquals(PostgresPreflightStatus.BLOCKED, report.status());
        assertNull(report.serverVersionNum());
        assertEquals(CapabilityCode.CONNECTION, report.capabilities().getFirst().code());
        assertFalse(report.toString().contains("secret"));
    }

    @Test
    void catalogPermissionFailureHasOwnCode() {
        var report = new CheckPostgresPreflight(() -> NOW, () -> {
            throw new PreflightProbeException(CapabilityCode.STATS_READABLE);
        }).check();
        assertEquals(CapabilityCode.STATS_READABLE, report.capabilities().getFirst().code());
    }

    private CapabilityResult result(PostgresPreflightReport report, CapabilityCode code) {
        return report.capabilities().stream().filter(result -> result.code() == code).findFirst().orElseThrow();
    }
}
