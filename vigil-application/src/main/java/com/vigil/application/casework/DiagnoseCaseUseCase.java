package com.vigil.application.casework;

import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.core.domain.Diagnosis;
import com.vigil.core.domain.DiagnosticEvaluation;
import com.vigil.core.domain.DiagnosticRuleEngine;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.RelationMetadataEvidencePayload;
import com.vigil.core.domain.TransitionMetadata;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/** Runs deterministic rules over evidence already attached to the case. */
public final class DiagnoseCaseUseCase {
    private static final Logger LOG = Logger.getLogger(DiagnoseCaseUseCase.class.getName());
    private final AnalysisCaseRepository repository;
    private final Clock clock;
    private final DiagnosticRuleEngine rules;

    public DiagnoseCaseUseCase(AnalysisCaseRepository repository, Clock clock, DiagnosticRuleEngine rules) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null.");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null.");
        this.rules = Objects.requireNonNull(rules, "Rules must not be null.");
    }

    public DiagnosticEvaluation execute(DiagnoseCaseCommand command) {
        var analysisCase = repository.getById(command.caseId());
        var plan = analysisCase.evidences().stream()
                .filter(item -> item.safePayload() instanceof PlanSummaryEvidencePayload)
                .findFirst().orElseThrow(() -> new IllegalStateException("PLAN_EVIDENCE_MISSING"));
        var catalogs = analysisCase.evidences().stream()
                .filter(item -> item.safePayload() instanceof RelationMetadataEvidencePayload relation
                        && relation.blueprint().isPresent())
                .map(item -> new DiagnosticRuleEngine.CatalogEvidence(item.id(),
                        ((RelationMetadataEvidencePayload) item.safePayload()).blueprint().orElseThrow()))
                .toList();
        var evaluation = rules.evaluate(new DiagnosticRuleEngine.PlanEvidence(plan.id(),
                (PlanSummaryEvidencePayload) plan.safePayload()), catalogs, command.selectedImpact());
        if (evaluation.findings().isEmpty()) {
            evaluation.inconclusiveReasons().forEach(reason -> LOG.info(reason::name));
            return evaluation;
        }
        var ids = new LinkedHashSet<java.util.UUID>();
        evaluation.findings().forEach(finding -> ids.addAll(finding.evidenceIds()));
        var first = evaluation.findings().getFirst();
        analysisCase.diagnose(new Diagnosis(evaluation.findings(), ids), new TransitionMetadata(command.actor(),
                first.ruleCode().name() + " — " + first.explanation(), clock.now()));
        repository.save(analysisCase);
        return evaluation;
    }
}
