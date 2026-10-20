package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.UuidIdGeneratorAdapter;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseAuditReader;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseRepository;
import com.vigil.adapters.sqlite.SqliteConnectionFactory;
import com.vigil.adapters.sqlite.SqliteDatabaseInitializer;
import com.vigil.application.casework.DiscoverObservedQueryCommand;
import com.vigil.application.casework.DiscoverObservedQueryResult;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseCommand;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.AuditedEvent;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Composition root of the persisted case flow: SQLite adapters, system clock and random ids wired into the
 * discover/triage use cases. It holds no domain rules and no JDBC.
 */
final class CaseFlow {
    static final String TRIAGE_ACTOR = "vigil-cli";
    static final String DISCOVERY_IDEMPOTENCY_KEY = "vigil-cli-first-flow";

    private static final Instant SCENARIO_OBSERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final DiscoverObservedQueryUseCase discover;
    private final TriageAnalysisCaseUseCase triage;
    private final AnalysisCaseRepository repository;
    private final AnalysisCaseAuditReader audit;

    private CaseFlow(SqliteConnectionFactory connections, Clock clock) {
        var ids = new UuidIdGeneratorAdapter();
        this.repository = new SqliteAnalysisCaseRepository(connections);
        this.audit = new SqliteAnalysisCaseAuditReader(connections);
        this.discover = new DiscoverObservedQueryUseCase(repository, clock, ids);
        this.triage = new TriageAnalysisCaseUseCase(repository, clock, ids);
    }

    /** Migrates the database at {@code databaseFile} (closing the connection, even on failure) and composes the flow. */
    static CaseFlow open(Path databaseFile) {
        var clock = new SystemClockAdapter();
        return new CaseFlow(SqliteDatabaseInitializer.initialize(databaseFile, clock), clock);
    }

    DiscoverObservedQueryResult discover() {
        return discover.execute(new DiscoverObservedQueryCommand(scenarioQuery(), DISCOVERY_IDEMPOTENCY_KEY));
    }

    void triage(UUID caseId, String reason) {
        triage.execute(new TriageAnalysisCaseCommand(caseId, reason, TRIAGE_ACTOR));
    }

    AnalysisCase find(UUID caseId) {
        return repository.getById(caseId);
    }

    List<AuditedEvent> auditOf(UUID caseId) {
        return audit.eventsOf(caseId);
    }

    /** The single deterministic scenario: a fixed SELECT observed from pg_stat_statements. */
    private static ObservedQuery scenarioQuery() {
        return new ObservedQuery(
                new QueryFingerprint("sha256:vigil-cli-first-flow", "v1"),
                TelemetrySource.PG_STAT_STATEMENTS,
                SCENARIO_OBSERVED_AT,
                Optional.empty(),
                CommandType.SELECT,
                new ObservationWindow(SCENARIO_OBSERVED_AT.minusSeconds(60), SCENARIO_OBSERVED_AT),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 100));
    }
}
