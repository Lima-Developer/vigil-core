package com.vigil.adapters.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.ValidationOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads a versioned isolated-laboratory result produced by the physical-index
 * runner and classifies it deterministically. Missing, unsafe, or non-comparable
 * results are deliberately INCONCLUSIVE rather than optimistic.
 */
public final class ArtifactHypothesisValidationAdapter implements HypothesisValidationPort {
    private final Path artifact;
    private final ObjectMapper mapper;

    public ArtifactHypothesisValidationAdapter(Path artifact) {
        this(artifact, new ObjectMapper());
    }

    ArtifactHypothesisValidationAdapter(Path artifact, ObjectMapper mapper) {
        this.artifact = Objects.requireNonNull(artifact, "Artifact path must not be null.");
        this.mapper = Objects.requireNonNull(mapper, "Object mapper must not be null.");
    }

    @Override
    public ValidationAssessment validate(AnalysisCase analysisCase) {
        Objects.requireNonNull(analysisCase, "Analysis case must not be null.");
        if (analysisCase.status() != CaseStatus.VALIDATING) {
            return new ValidationAssessment(ValidationOutcome.INCONCLUSIVE,
                    "Classificação automática exige caso no estado VALIDATING.");
        }
        try {
            if (!Files.isRegularFile(artifact)) return inconclusive("Artefato de validação isolada indisponível.");
            JsonNode root = mapper.readTree(Files.readString(artifact));
            JsonNode safety = root.path("safety");
            JsonNode before = root.path("before");
            JsonNode after = root.path("after");
            if (root.path("schemaVersion").asInt() != 1 || safety.path("targetDatabaseAccessed").asBoolean(true)
                    || !safety.path("physicalIndexCreated").asBoolean(false)
                    || !safety.path("explainAnalyzeUsed").asBoolean(false)) {
                return inconclusive("Artefato não comprova avaliação física isolada e segura.");
            }
            double beforeMs = before.path("executionTimeMs").asDouble(-1);
            double afterMs = after.path("executionTimeMs").asDouble(-1);
            String indexName = after.path("indexName").asText("");
            if (beforeMs < 0 || afterMs < 0 || indexName.isBlank()) return inconclusive("Métricas de plano incompletas.");
            if (afterMs < beforeMs) {
                return new ValidationAssessment(ValidationOutcome.VALIDATED,
                        "Laboratório isolado: índice " + indexName + " reduziu execução de "
                                + beforeMs + " ms para " + afterMs + " ms.");
            }
            return new ValidationAssessment(ValidationOutcome.INVALIDATED,
                    "Laboratório isolado não demonstrou redução de tempo para a hipótese avaliada.");
        } catch (IOException | RuntimeException malformed) {
            return inconclusive("Artefato de validação não pôde ser interpretado com segurança.");
        }
    }

    private static ValidationAssessment inconclusive(String rationale) {
        return new ValidationAssessment(ValidationOutcome.INCONCLUSIVE, rationale);
    }
}
