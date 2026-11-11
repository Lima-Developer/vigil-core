package com.vigil.bootstrap;

import com.vigil.adapters.postgres.PostgresExecutionPlanSource;
import com.vigil.application.casework.DiscoverObservedQueryCommand;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseCommand;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
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
    private final AnalysisCaseRepository cases;
    private final AnalysisCaseAuditReader audit;
    private final DiscoverObservedQueryUseCase discover;
    private final TriageAnalysisCaseUseCase triage;
    private final Clock clock;
    private final TargetProfile target;
    private final VigilProperties properties;

    DemoCaseController(AnalysisCaseRepository cases, AnalysisCaseAuditReader audit,
                       DiscoverObservedQueryUseCase discover, TriageAnalysisCaseUseCase triage,
                       Clock clock, TargetProfile target, VigilProperties properties) {
        this.cases = cases;
        this.audit = audit;
        this.discover = discover;
        this.triage = triage;
        this.clock = clock;
        this.target = target;
        this.properties = properties;
    }

    @GetMapping("/demo")
    CaseView show() {
        return view(cases.findByIdempotencyKey(KEY).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso ainda não criado")));
    }

    @PostMapping("/demo")
    CaseView create() {
        Instant now = clock.now();
        var query = new ObservedQuery(new QueryFingerprint("sha256:vigil-web-demo", "v1"),
                TelemetrySource.PG_STAT_STATEMENTS, now, Optional.empty(), CommandType.SELECT,
                new ObservationWindow(now.minusSeconds(60), now),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 100));
        var result = discover.execute(new DiscoverObservedQueryCommand(query, KEY));
        return view(cases.getById(result.caseId()));
    }

    @PostMapping("/{id}/triage")
    CaseView triage(@PathVariable("id") UUID id, @RequestBody TriageRequest request) {
        var current = cases.findByIdempotencyKey(KEY).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso ainda não criado"));
        if (!current.caseId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado");
        if (request == null || request.reason() == null || request.reason().trim().length() < 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Justificativa deve ter ao menos 8 caracteres");
        triage.execute(new TriageAnalysisCaseCommand(id, request.reason().trim(), ACTOR));
        return view(cases.getById(id));
    }

    private CaseView view(AnalysisCase analysisCase) {
        Instant observed = analysisCase.createdAt();
        var events = new ArrayList<EventView>();
        for (var event : audit.eventsOf(analysisCase.caseId()))
            events.add(new EventView(event.type(), event.actor(), event.occurredAt()));
        for (var event : analysisCase.events())
            events.add(new EventView("Transição: " + event.toStatus(), event.reason(), event.occuredAt()));
        events.sort(Comparator.comparing(EventView::at));
        return new CaseView(analysisCase.caseId(), analysisCase.status().name(),
                "fixture de laboratório", "SELECT", observed,
                new WindowView(observed.minusSeconds(60), observed),
                new MetricsView(10, 2000, 200, 100), plan(), List.copyOf(events));
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
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Caso já triado"));
    }

    record TriageRequest(String reason) { }
    record EventView(String type, String detail, Instant at) { }
    record WindowView(Instant start, Instant end) { }
    record MetricsView(long calls, long totalExecutionMillis, long meanExecutionMillis, long rows) { }
    record PlanView(boolean estimated, String text) { }
    record CaseView(UUID id, String status, String source, String command, Instant observedAt,
                    WindowView window, MetricsView metrics, PlanView explain, List<EventView> events) { }
}
