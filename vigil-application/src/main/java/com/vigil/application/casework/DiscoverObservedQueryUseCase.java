package com.vigil.application.casework;

import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.DomainEventMetadata;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens a case for an observed query exactly once per idempotency key. The case is created at {@code clock.now()}
 * and records an {@code AnalysisCaseDiscovered} event attributed to {@link #SYSTEM_ACTOR}, with a distinct event id
 * and the stable event version {@link #EVENT_VERSION}.
 */
public final class DiscoverObservedQueryUseCase {
    public static final String SYSTEM_ACTOR = "vigil-system";
    public static final String EVENT_VERSION = "1";

    private final AnalysisCaseRepository repository;
    private final Clock clock;
    private final IdGenerator idGenerator;

    public DiscoverObservedQueryUseCase(AnalysisCaseRepository repository, Clock clock, IdGenerator idGenerator) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
        this.idGenerator = Objects.requireNonNull(idGenerator, "Id generator must not be null.");
    }

    /**
     * Opens a case from a {@link Candidate} selected by impact, converting it to an {@link ObservedQuery}: the family
     * fingerprint, the first source query id for traceability and metrics derived from the impact delta. The
     * {@code SelectionReason} is not recorded here — it is the suggested triage reason, recorded on the triage event.
     */
    public DiscoverObservedQueryResult execute(Candidate candidate, TelemetrySource source, Instant observedAt,
                                               ObservationWindow window, CommandType commandType, String idempotencyKey) {
        Objects.requireNonNull(candidate, "Candidate must not be null.");
        var delta = candidate.assessment().delta();
        var metrics = new ObservedQueryMetrics(delta.calls(),
                Duration.ofMillis(delta.totalExecutionMillis().setScale(0, RoundingMode.HALF_UP).longValueExact()),
                delta.rows());
        var query = new ObservedQuery(candidate.fingerprint(), source, observedAt,
                Optional.of(candidate.sourceQueryIds().get(0)), commandType, window, metrics);
        return execute(new DiscoverObservedQueryCommand(query, idempotencyKey));
    }

    public DiscoverObservedQueryResult execute(DiscoverObservedQueryCommand command) {
        var existing = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) return new DiscoverObservedQueryResult(existing.get().caseId(), false);

        var now = clock.now();
        var caseId = idGenerator.nextId();
        var metadata = new DomainEventMetadata(idGenerator.nextId(), SYSTEM_ACTOR, now, EVENT_VERSION);
        var analysisCase = AnalysisCase.discover(caseId, now, command.observedQuery(), metadata);
        try {
            repository.saveDiscovered(analysisCase, command.idempotencyKey());
            return new DiscoverObservedQueryResult(analysisCase.caseId(), true);
        } catch (ConcurrentCaseUpdate conflict) {
            return repository.findByIdempotencyKey(command.idempotencyKey())
                    .map(caseFound -> new DiscoverObservedQueryResult(caseFound.caseId(), false))
                    .orElseThrow(() -> conflict);
        }
    }
}
