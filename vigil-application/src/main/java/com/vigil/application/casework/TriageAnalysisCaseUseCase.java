package com.vigil.application.casework;

import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.DomainEventMetadata;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.util.Objects;

/**
 * Triages a case and records {@code QueryTriaged} attributed to the command actor, with a distinct event id and the
 * stable event version {@link DiscoverObservedQueryUseCase#EVENT_VERSION}.
 */
public final class TriageAnalysisCaseUseCase {
    private final AnalysisCaseRepository repository;
    private final Clock clock;
    private final IdGenerator idGenerator;

    public TriageAnalysisCaseUseCase(AnalysisCaseRepository repository, Clock clock, IdGenerator idGenerator) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
        this.idGenerator = Objects.requireNonNull(idGenerator, "Id generator must not be null.");
    }

    public void execute(TriageAnalysisCaseCommand command) {
        var analysisCase = repository.getById(command.caseId());
        try {
            var now = clock.now();
            var metadata = new DomainEventMetadata(idGenerator.nextId(), command.actor(), now,
                    DiscoverObservedQueryUseCase.EVENT_VERSION);
            analysisCase.triage(command.reason(), metadata);
        } catch (InvalidCaseTransitionException exception) {
            throw new InvalidAnalysisCaseTransition(exception);
        }
        repository.save(analysisCase);
    }
}
