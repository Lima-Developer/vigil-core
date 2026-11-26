package com.vigil.application.casework;

import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.Recommendation;
import com.vigil.core.domain.enums.RecommendationRisk;
import com.vigil.core.domain.enums.RecommendationLimitation;
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
        if (!command.queryShape().automaticValidationSupported()) {
            throw new IllegalArgumentException("UNSUPPORTED_QUERY_SHAPE");
        }
        var analysisCase = repository.getById(command.caseId());
        var assessment = validator.validate(analysisCase);
        var at = clock.now();
        try {
            analysisCase.recordValidation(assessment.outcome(),
                    new TransitionMetadata(command.actor(), assessment.rationale(), at));
            var hypothesis = analysisCase.hypothesis().orElseThrow(() ->
                    new IllegalStateException("Optimization hypothesis is not available."));
            var recommendation = new Recommendation(analysisCase.recommendationVersion() + 1, hypothesis,
                    assessment.outcome(), java.util.Set.copyOf(hypothesis.origin().evidenceIds()),
                    java.util.List.of(RecommendationRisk.PLAN_VARIABILITY),
                    java.util.List.of(RecommendationLimitation.SYNTHETIC_DATA_ONLY,
                            RecommendationLimitation.NO_REAL_TARGET_VALIDATION));
            analysisCase.prepareRecommendation(recommendation, new TransitionMetadata(command.actor(),
                    "Classificação automática concluída; aguardando decisão humana.", at));
        } catch (InvalidCaseTransitionException exception) {
            throw new InvalidAnalysisCaseTransition(exception);
        }
        repository.save(analysisCase);
        return assessment;
    }
}
