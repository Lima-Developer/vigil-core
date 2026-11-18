package com.vigil.application.casework;

import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.exception.InvalidCaseTransitionException;
import java.util.Objects;

/** Records an auditable human disposition after the aggregate reaches AWAITING_HUMAN. */
public final class RecordHumanDecisionUseCase {
    private final AnalysisCaseRepository repository;
    private final Clock clock;

    public RecordHumanDecisionUseCase(AnalysisCaseRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
    }

    public void execute(RecordHumanDecisionCommand command) {
        var analysisCase = repository.getById(command.caseId());
        var metadata = new TransitionMetadata(command.actor(), command.reason(), clock.now());
        try {
            switch (command.decision()) {
                case APPROVE -> analysisCase.approve(metadata);
                case REJECT -> analysisCase.reject(metadata);
                case REQUEST_CHANGES -> analysisCase.requestChanges(metadata);
            }
        } catch (InvalidCaseTransitionException exception) {
            throw new InvalidAnalysisCaseTransition(exception);
        }
        repository.save(analysisCase);
    }
}
