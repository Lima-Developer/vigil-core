package com.vigil.application.casework;

import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.domain.TransitionMetadata;

import java.util.Objects;

public final class ProposeHypothesisUseCase {
    private final AnalysisCaseRepository repository;
    private final Clock clock;

    public ProposeHypothesisUseCase(AnalysisCaseRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
    }

    public void execute(ProposeHypothesisCommand command) {
        var analysisCase = repository.getById(command.caseId());
        analysisCase.proposeHypothesis(command.hypothesis(),
                new TransitionMetadata(command.actor(), "Hipótese de índice derivada de finding determinístico.", clock.now()));
        repository.save(analysisCase);
    }
}
