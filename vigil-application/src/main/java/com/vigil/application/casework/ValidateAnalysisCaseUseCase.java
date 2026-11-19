package com.vigil.application.casework;

import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.exception.InvalidCaseTransitionException;
import java.util.Objects;

/**
 * Chains isolated evidence evaluation to the auditable workflow: VALIDATING →
 * outcome → AWAITING_HUMAN. The resulting recommendation still requires an
 * explicit human decision and never applies a database change.
 */
public final class ValidateAnalysisCaseUseCase {
    private final AnalysisCaseRepository repository;
    private final HypothesisValidationPort validator;
    private final Clock clock;

    public ValidateAnalysisCaseUseCase(AnalysisCaseRepository repository, HypothesisValidationPort validator, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.validator = Objects.requireNonNull(validator, "Validator must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
    }

    public HypothesisValidationPort.ValidationAssessment execute(ValidateAnalysisCaseCommand command) {
        var analysisCase = repository.getById(command.caseId());
        var assessment = validator.validate(analysisCase);
        var at = clock.now();
        try {
            analysisCase.recordValidation(assessment.outcome(),
                    new TransitionMetadata(command.actor(), assessment.rationale(), at));
            analysisCase.prepareRecommendation(new TransitionMetadata(command.actor(),
                    "Classificação automática concluída; aguardando decisão humana.", at));
        } catch (InvalidCaseTransitionException exception) {
            throw new InvalidAnalysisCaseTransition(exception);
        }
        repository.save(analysisCase);
        return assessment;
    }
}
