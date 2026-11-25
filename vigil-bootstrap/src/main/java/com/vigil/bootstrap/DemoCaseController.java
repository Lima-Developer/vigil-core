package com.vigil.bootstrap;

import com.vigil.adapters.postgres.PostgresExecutionPlanSource;
import com.vigil.application.casework.DiscoverObservedQueryCommand;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.HumanDecision;
import com.vigil.application.casework.RecordHumanDecisionCommand;
import com.vigil.application.casework.RecordHumanDecisionUseCase;
import com.vigil.application.casework.TriageAnalysisCaseCommand;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.casework.ValidateAnalysisCaseCommand;
import com.vigil.application.casework.ValidateAnalysisCaseUseCase;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.casework.SelectCandidatesByImpactUseCase;
import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import com.vigil.application.port.out.interfaces.FingerprintOutcome;
import com.vigil.application.port.out.interfaces.FingerprintedQueryCounters;
import com.vigil.application.port.out.interfaces.ImpactTelemetrySnapshot;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import com.vigil.application.port.out.interfaces.QuerySanitizer;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.enums.TelemetrySource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local, single-case demonstration. Target access is read-only; raw SQL never enters the response. */
@RestController
@RequestMapping("/api/cases")
class DemoCaseController {
    private static final String KEY = "vigil-web-demo-v1";
    private static final String ACTOR = "vigil-web-demo";
    /** The deterministic fixture query of the demonstration; its fingerprint is computed, never hardcoded. */
    private static final String DEMO_SQL =
            "SELECT id, total FROM orders WHERE customer_id = 42 AND status = 'OPEN' "
                    + "ORDER BY created_at DESC LIMIT 20";
    /** A cheap, high-call query that must NOT be selected (criterion 6): many calls, negligible total time. */
    private static final String DEMO_CHEAP_SQL = "SELECT 1";
    private static final Instant SNAPSHOT_BEFORE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SNAPSHOT_AFTER = Instant.parse("2026-01-01T00:05:00Z");
    private static final Instant SNAPSHOT_RESET = Instant.parse("2026-01-01T00:00:00Z");
    private final AnalysisCaseRepository cases;
    private final AnalysisCaseAuditReader audit;
    private final DiscoverObservedQueryUseCase discover;
    private final TriageAnalysisCaseUseCase triage;
    private final RecordHumanDecisionUseCase humanDecisions;
    private final ValidateAnalysisCaseUseCase validation;
    private final Clock clock;
    private final TargetProfile target;
    private final VigilProperties properties;
    private final QueryFingerprinter fingerprinter;
    private final QuerySanitizer sanitizer;
    private final SelectCandidatesByImpactUseCase selectCandidates;

    DemoCaseController(AnalysisCaseRepository cases, AnalysisCaseAuditReader audit,
                       DiscoverObservedQueryUseCase discover, TriageAnalysisCaseUseCase triage,
                       RecordHumanDecisionUseCase humanDecisions,
                       ValidateAnalysisCaseUseCase validation,
                       Clock clock, TargetProfile target, VigilProperties properties,
                       @Lazy QueryFingerprinter fingerprinter, QuerySanitizer sanitizer,
                       SelectCandidatesByImpactUseCase selectCandidates) {
        this.cases = cases;
        this.audit = audit;
        this.discover = discover;
        this.triage = triage;
        this.humanDecisions = humanDecisions;
        this.validation = validation;
        this.clock = clock;
        this.target = target;
        this.properties = properties;
        this.fingerprinter = fingerprinter;
        this.sanitizer = sanitizer;
        this.selectCandidates = selectCandidates;
    }

    @GetMapping("/demo")
    CaseView show() {
        return view(cases.findByIdempotencyKey(KEY).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso ainda não criado")));
    }

    @PostMapping("/demo")
    CaseView create() {
        Instant now = clock.now();
        // The case is born from a Candidate selected by the impact policy over two fixture snapshots, not a fixed query.
        var candidate = demoCandidate();
        var window = new ObservationWindow(now.minusSeconds(60), now);
        var result = discover.execute(candidate, TelemetrySource.PG_STAT_STATEMENTS, now, window,
                CommandType.SELECT, KEY);
        return view(cases.getById(result.caseId()));
    }

    /** Runs the impact policy over two deterministic fixture snapshots; the heavy query wins, the cheap one does not. */
    private Candidate demoCandidate() {
        var heavy = fingerprintOf(DEMO_SQL);
        var cheap = fingerprintOf(DEMO_CHEAP_SQL);
        var before = new ImpactTelemetrySnapshot(SNAPSHOT_BEFORE, Optional.of(SNAPSHOT_RESET), 0, true, List.of(
                fixtureEntry(101, 0, "0", heavy), fixtureEntry(202, 0, "0", cheap)), 0);
        var after = new ImpactTelemetrySnapshot(SNAPSHOT_AFTER, Optional.of(SNAPSHOT_RESET), 0, true, List.of(
                fixtureEntry(101, 56, "1234", heavy), fixtureEntry(202, 10000, "50", cheap)), 0);
        var result = selectCandidates.execute(before, after);
        if (result.candidates().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Nenhuma candidata de impacto na fixture da demonstração: " + result.outcome());
        }
        return result.candidates().get(0);
    }

    private static FingerprintedQueryCounters fixtureEntry(long queryId, long calls, String totalMillis,
                                                           QueryFingerprint fingerprint) {
        var counters = new CumulativeQueryCounters(1, 1, queryId, true, calls, new java.math.BigDecimal(totalMillis),
                calls, 0, 0, 0, 0);
        return new FingerprintedQueryCounters(counters, Optional.of(fingerprint));
    }

    /** Computes the demo fingerprint via the port; an unsupported fixture surfaces as an error, never masked. */
    private QueryFingerprint fingerprintOf(String sql) {
        return switch (fingerprinter.fingerprint(sql)) {
            case FingerprintOutcome.Supported supported -> supported.fingerprint();
            case FingerprintOutcome.Unsupported unsupported ->
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "SQL da fixture não suportado pelo fingerprint: " + unsupported.reason());
        };
    }

    /** Sanitizes the demo fixture via the port; a BLOCKED fixture surfaces as an error, never masked. */
    private SanitizationResult.Safe safeContextOf(String sql) {
        return switch (sanitizer.sanitize(sql)) {
            case SanitizationResult.Safe safe -> safe;
            case SanitizationResult.Blocked blocked ->
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "SQL da fixture bloqueado na sanitização: " + blocked.reason());
        };
    }

    @PostMapping("/{id}/triage")
    CaseView triage(@PathVariable("id") UUID id, @RequestBody TriageRequest request) {
        requireDemoCase(id);
        if (request == null || request.reason() == null || request.reason().trim().length() < 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Justificativa deve ter ao menos 8 caracteres");
        triage.execute(new TriageAnalysisCaseCommand(id, request.reason().trim(), ACTOR));
        return view(cases.getById(id));
    }

    /** Builds fixture evidence, then chains isolated automatic classification; it never calls the monitored database. */
    @PostMapping("/{id}/prepare-human-review")
    CaseView prepareHumanReview(@PathVariable("id") UUID id) {
        var analysisCase = requireDemoCase(id);
        if (analysisCase.status() != com.vigil.core.domain.enums.CaseStatus.TRIAGED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O caso precisa estar triado antes da curadoria");
        var now = clock.now();
        var payload = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 100,
                Optional.of(new BigDecimal("12.00")));
        var evidence = new Evidence(UUID.randomUUID(), id, payload.type(), EvidenceSource.POSTGRESQL_EXPLAIN,
                now, "vigil-web-demo-v1", EvidenceConfidence.HIGH, payload, Optional.empty());
        var metadata = new TransitionMetadata(ACTOR, "Fixture de laboratório preparada para demonstração.", now);
        analysisCase.collectEvidence(evidence, ACTOR, now);
        analysisCase.sanitize(safeContextOf(DEMO_SQL), metadata);
        analysisCase.diagnose(metadata);
        analysisCase.proposeHypothesis(metadata);
        analysisCase.startValidation(metadata);
        cases.save(analysisCase);
        validation.execute(new ValidateAnalysisCaseCommand(id, ACTOR));
        return view(cases.getById(id));
    }

    @PostMapping("/{id}/decision")
    CaseView recordDecision(@PathVariable("id") UUID id, @RequestBody HumanDecisionRequest request) {
        requireDemoCase(id);
        if (request == null || request.decision() == null || request.reason() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma decisão e informe a justificativa");
        try {
            humanDecisions.execute(new RecordHumanDecisionCommand(id, request.decision(), request.reason(), ACTOR));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        return view(cases.getById(id));
    }

    private AnalysisCase requireDemoCase(UUID id) {
        var current = cases.findByIdempotencyKey(KEY).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso ainda não criado"));
        if (!current.caseId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado");
        return current;
    }

    private CaseView view(AnalysisCase analysisCase) {
        Instant observed = analysisCase.createdAt();
        var events = new ArrayList<EventView>();
        for (var event : audit.eventsOf(analysisCase.caseId()))
            events.add(new EventView(event.type(), "Evento de domínio persistido.", event.actor(), event.occurredAt()));
        for (var event : analysisCase.events())
            events.add(new EventView("Transição: " + event.toStatus(), event.reason(), event.actor(), event.occuredAt()));
        events.sort(Comparator.comparing(EventView::at));
        return new CaseView(analysisCase.caseId(), analysisCase.status().name(),
                "fixture de laboratório", "SELECT", observed,
                new WindowView(observed.minusSeconds(60), observed),
                new MetricsView(10, 2000, 200, 100), plan(), List.copyOf(events),
                demoCandidate().selectionReason().render());
    }

    private PlanView plan() {
        var demo = properties.demo();
        if (demo == null || demo.select() == null || demo.select().isBlank()
                || demo.postgresUser() == null || demo.postgresUser().isBlank())
            return new PlanView(true, "Plano de fixture de laboratório: Seq Scan, 100 linhas e custo estimado 12,00. Não é EXPLAIN coletado; configure um target de leitura para coletá-lo.");
        try {
            var source = PostgresExecutionPlanSource.forTarget(target, demo.postgresUser(),
                    demo.sslRootCert(), System.getenv());
            var summary = source.collect(demo.select());
            return new PlanView(true, "Nós: " + summary.nodeTypes() + "; linhas estimadas: "
                    + summary.estimatedRows() + "; custo estimado: "
                    + summary.estimatedTotalCost().map(java.math.BigDecimal::toPlainString).orElse("indisponível")
                    + ". O custo do plano não representa ganho medido.");
        } catch (RuntimeException failure) {
            return new PlanView(true, "EXPLAIN indisponível no laboratório. Verifique conexão e política de leitura.");
        }
    }

    @ExceptionHandler(com.vigil.application.exception.InvalidAnalysisCaseTransition.class)
    ResponseEntity<Map<String, String>> invalidTransition() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Transição não permitida para o estado atual do caso"));
    }

    record TriageRequest(String reason) { }
    record HumanDecisionRequest(HumanDecision decision, String reason) { }
    record EventView(String type, String detail, String actor, Instant at) { }
    record WindowView(Instant start, Instant end) { }
    record MetricsView(long calls, long totalExecutionMillis, long meanExecutionMillis, long rows) { }
    record PlanView(boolean estimated, String text) { }
    record CaseView(UUID id, String status, String source, String command, Instant observedAt,
                    WindowView window, MetricsView metrics, PlanView explain, List<EventView> events,
                    String selectionReason) { }
}
