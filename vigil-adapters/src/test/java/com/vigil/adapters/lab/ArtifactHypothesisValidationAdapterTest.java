package com.vigil.adapters.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.enums.ValidationOutcome;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactHypothesisValidationAdapterTest {
    @Test
    void validatesAnIsolatedPhysicalImprovement() throws Exception {
        var artifact = Files.createTempFile("vigil-validation", ".json");
        Files.writeString(artifact, """
                {"schemaVersion":1,"safety":{"targetDatabaseAccessed":false,"physicalIndexCreated":true,"explainAnalyzeUsed":true},
                "before":{"executionTimeMs":12.0},"after":{"executionTimeMs":2.0,"indexName":"orders_cover_idx"}}
                """);
        var result = new ArtifactHypothesisValidationAdapter(artifact).validate(validatingCase());
        assertEquals(ValidationOutcome.VALIDATED, result.outcome());
    }

    @Test
    void refusesAnArtifactThatTouchedTheTarget() throws Exception {
        var artifact = Files.createTempFile("vigil-validation", ".json");
        Files.writeString(artifact, """
                {"schemaVersion":1,"safety":{"targetDatabaseAccessed":true,"physicalIndexCreated":true,"explainAnalyzeUsed":true}}
                """);
        var result = new ArtifactHypothesisValidationAdapter(artifact).validate(validatingCase());
        assertEquals(ValidationOutcome.INCONCLUSIVE, result.outcome());
    }

    @Test
    void invalidatesAComparableExperimentWithoutMeasuredImprovement() throws Exception {
        var artifact = Files.createTempFile("vigil-validation", ".json");
        Files.writeString(artifact, """
                {"schemaVersion":1,"safety":{"targetDatabaseAccessed":false,"physicalIndexCreated":true,"explainAnalyzeUsed":true},
                "before":{"executionTimeMs":12.0},"after":{"executionTimeMs":12.5,"indexName":"orders_cover_idx"}}
                """);
        var result = new ArtifactHypothesisValidationAdapter(artifact).validate(validatingCase());
        assertEquals(ValidationOutcome.INVALIDATED, result.outcome());
    }

    private static AnalysisCase validatingCase() {
        var now = Instant.parse("2026-11-18T12:00:00Z");
        var analysisCase = AnalysisCase.discover(UUID.randomUUID(), now);
        analysisCase.triage(new TransitionMetadata("test", "triagem válida", now));
        var evidence = new Evidence(UUID.randomUUID(), analysisCase.caseId(),
                new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 10,
                        Optional.of(BigDecimal.ONE)).type(), EvidenceSource.POSTGRESQL_EXPLAIN, now,
                "test", EvidenceConfidence.HIGH,
                new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 10, Optional.of(BigDecimal.ONE)),
                Optional.empty());
        analysisCase.collectEvidence(evidence, "test", now);
        var metadata = new TransitionMetadata("test", "evidência suficiente", now);
        analysisCase.sanitize(
                new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)), metadata);
        analysisCase.diagnose(metadata);
        analysisCase.proposeHypothesis(metadata);
        analysisCase.startValidation(metadata);
        return analysisCase;
    }
}
