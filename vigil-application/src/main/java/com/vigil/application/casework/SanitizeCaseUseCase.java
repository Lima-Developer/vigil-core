package com.vigil.application.casework;

import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.QuerySanitizer;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.TransitionMetadata;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Sanitizes the authorized SQL of a case through the {@link QuerySanitizer} port and advances the case to
 * {@code SANITIZED} only on a {@link SanitizationResult.Safe}. On a {@link SanitizationResult.Blocked} the case does
 * not advance: there is no valid terminal transition for a sanitization block from the state where sanitize happens,
 * so the case stays in its current state and the use case logs only the block reason (an enum, never the SQL) and
 * returns the {@code Blocked} to the caller. The {@code CaseEvent}/reconstitution semantics are untouched.
 */
public final class SanitizeCaseUseCase {
    private static final Logger LOG = System.getLogger(SanitizeCaseUseCase.class.getName());

    private final AnalysisCaseRepository repository;
    private final QuerySanitizer sanitizer;
    private final Clock clock;

    public SanitizeCaseUseCase(AnalysisCaseRepository repository, QuerySanitizer sanitizer, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.sanitizer = Objects.requireNonNull(sanitizer, "Sanitizer must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
    }

    public SanitizationResult execute(SanitizeCaseCommand command) {
        Objects.requireNonNull(command, "Command must not be null.");
        var result = sanitizer.sanitize(command.sql());
        if (result instanceof SanitizationResult.Safe safe) {
            var analysisCase = repository.getById(command.caseId());
            analysisCase.sanitize(safe, new TransitionMetadata(command.actor(),
                    "Contexto seguro gerado (" + safe.appliedRules() + ").", clock.now()));
            repository.save(analysisCase);
        } else if (result instanceof SanitizationResult.Blocked blocked) {
            // Enum only, never the SQL: the case stays put and the caller decides what to do with the block.
            LOG.log(Level.WARNING, "Sanitização bloqueada para o caso {0}: {1}", command.caseId(), blocked.reason());
        }
        return result;
    }
}
