package com.vigil.application.casework;

import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.exception.InvalidCaseTransitionException;

import java.util.Objects;

public final class TriageAnalysisCaseUseCase {
    private final AnalysisCaseRepository repository;
    private final Clock clock;

    public TriageAnalysisCaseUseCase(AnalysisCaseRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
    }

    public void execute(TriageAnalysisCaseCommand command) {
        var analysisCase = repository.getById(command.caseId());
        try {
            analysisCase.triage(command.reason(), command.actor(), clock.now());
        } catch (InvalidCaseTransitionException exception) {
            throw new InvalidAnalysisCaseTransition(exception);
        }
        repository.save(analysisCase);
    }
}
