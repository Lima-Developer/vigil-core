package com.vigil.application.casework;

import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.AnalysisCase;

import java.util.Objects;

public final class DiscoverObservedQueryUseCase {
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

        var analysisCase = AnalysisCase.discover(idGenerator.nextId(), clock.now(), command.observedQuery());
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
