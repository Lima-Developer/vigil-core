package com.vigil.application.preflight;

import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CheckPostgresPreflight {
    private static final int MINIMUM_VERSION = 140000;

    private final Clock clock;
    private final PostgresCapabilityProbe probe;

    public CheckPostgresPreflight(Clock clock, PostgresCapabilityProbe probe) {
        this.clock = Objects.requireNonNull(clock, "Clock não pode ser nulo");
        this.probe = Objects.requireNonNull(probe, "PostgresCapabilityProbe não pode ser nulo");
    }

    public PostgresPreflightReport check() {
        Instant checkedAt = Objects.requireNonNull(clock.now(), "Clock retornou nulo");
        PostgresPreflightFacts facts;
        try {
            facts = Objects.requireNonNull(probe.inspect(), "Probe retornou nulo");
        } catch (Exception failure) {
            CapabilityCode code = failure instanceof PreflightProbeException categorized
                    ? categorized.code() : CapabilityCode.CONNECTION;
            return new PostgresPreflightReport(PostgresPreflightStatus.BLOCKED, checkedAt, null, null,
                    List.of(new CapabilityResult(code, CapabilityStatus.MISSING, remediation(code))));
        }

        var results = new ArrayList<CapabilityResult>();
        add(results, CapabilityCode.CONNECTION, CapabilityStatus.AVAILABLE);
        add(results, CapabilityCode.SERVER_VERSION,
                facts.serverVersionNum() >= MINIMUM_VERSION ? CapabilityStatus.AVAILABLE : CapabilityStatus.MISSING);
        add(results, CapabilityCode.PG_STAT_STATEMENTS_AVAILABLE, present(facts.extensionAvailable()));
        add(results, CapabilityCode.PG_STAT_STATEMENTS_INSTALLED, present(facts.extensionInstalled()));
        add(results, CapabilityCode.PG_STAT_STATEMENTS_PRELOADED, present(facts.preloaded()));
        add(results, CapabilityCode.STATS_READABLE, present(facts.statsReadable()));
        String queryId = facts.computeQueryId();
        add(results, CapabilityCode.COMPUTE_QUERY_ID, queryId == null ? CapabilityStatus.UNKNOWN
                : ("on".equalsIgnoreCase(queryId) || "auto".equalsIgnoreCase(queryId) && facts.preloaded())
                ? CapabilityStatus.AVAILABLE : CapabilityStatus.MISSING);
        String ioTiming = facts.trackIoTiming();
        add(results, CapabilityCode.TRACK_IO_TIMING, ioTiming == null ? CapabilityStatus.UNKNOWN
                : "on".equalsIgnoreCase(ioTiming) ? CapabilityStatus.AVAILABLE : CapabilityStatus.MISSING);

        boolean blocked = results.stream().anyMatch(result -> result.code() != CapabilityCode.TRACK_IO_TIMING
                && result.status() != CapabilityStatus.AVAILABLE);
        boolean degraded = results.stream().anyMatch(result -> result.code() == CapabilityCode.TRACK_IO_TIMING
                && result.status() != CapabilityStatus.AVAILABLE);
        return new PostgresPreflightReport(blocked ? PostgresPreflightStatus.BLOCKED
                : degraded ? PostgresPreflightStatus.DEGRADED : PostgresPreflightStatus.READY,
                checkedAt, facts.serverVersionNum(), facts.databaseName(), results);
    }

    private static CapabilityStatus present(boolean value) {
        return value ? CapabilityStatus.AVAILABLE : CapabilityStatus.MISSING;
    }

    private static void add(List<CapabilityResult> results, CapabilityCode code, CapabilityStatus status) {
        results.add(new CapabilityResult(code, status,
                status == CapabilityStatus.AVAILABLE ? "" : remediation(code)));
    }

    private static String remediation(CapabilityCode code) {
        return switch (code) {
            case CONNECTION -> "Verifique a conectividade e as credenciais do target com o administrador.";
            case SERVER_VERSION -> "Use PostgreSQL 14 ou superior.";
            case PG_STAT_STATEMENTS_AVAILABLE -> "Peça ao DBA para instalar o pacote contrib ou ajustar a imagem PostgreSQL.";
            case PG_STAT_STATEMENTS_INSTALLED -> "Peça ao DBA para executar CREATE EXTENSION pg_stat_statements;";
            case PG_STAT_STATEMENTS_PRELOADED -> "Peça ao DBA para configurar shared_preload_libraries com pg_stat_statements e reiniciar o servidor.";
            case STATS_READABLE -> "Peça ao DBA para conceder pg_read_all_stats ao papel do Vigil.";
            case COMPUTE_QUERY_ID -> "Peça ao DBA para configurar compute_query_id = auto e recarregar a configuração.";
            case TRACK_IO_TIMING -> "Peça ao DBA para configurar track_io_timing = on e recarregar a configuração; métricas de I/O indisponíveis até lá.";
        };
    }
}
