package com.vigil.bootstrap;

import com.vigil.application.casework.DiscoverObservedQueryCommand;
import com.vigil.application.casework.DiscoverObservedQueryResult;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseCommand;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.AuditedEvent;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;

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

    CaseFlow(DiscoverObservedQueryUseCase discover, TriageAnalysisCaseUseCase triage,
             AnalysisCaseRepository repository, AnalysisCaseAuditReader audit) {
        this.repository = repository;
        this.audit = audit;
        this.discover = discover;
        this.triage = triage;
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
