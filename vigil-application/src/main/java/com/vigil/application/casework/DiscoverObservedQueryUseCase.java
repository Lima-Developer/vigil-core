package com.vigil.application.casework;

import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.DomainEventMetadata;

import java.util.Objects;

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
