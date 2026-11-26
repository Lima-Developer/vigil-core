package com.vigil.application.port.out.interfaces;

import com.vigil.application.inference.PromptTemplateVersion;
import com.vigil.application.inference.SanitizedInferenceContext;
import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Provider-independent, bounded text inference port. */
public interface InferencePort {
    InferenceResponse infer(InferenceRequest request);

    record InferenceRequest(String model, SanitizedInferenceContext context, OutputSchema outputSchema,
                            PromptTemplateVersion templateVersion, Duration timeout) {
        public InferenceRequest {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model must not be blank.");
            Objects.requireNonNull(context, "Approved context must not be null.");
            Objects.requireNonNull(outputSchema, "Output schema must not be null.");
            Objects.requireNonNull(templateVersion, "Prompt template version must not be null.");
            if (timeout == null || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("Timeout must be positive.");
        }
    }

    enum OutputSchema { DIAGNOSIS_V1, INDEX_HYPOTHESIS_V1 }

    record GroundedOutput(OutputSchema schema, String explanation, Set<UUID> evidenceIds,
                          Optional<SafeRelationReference> relation, List<SafeCatalogReference> columns) {
        public GroundedOutput {
            Objects.requireNonNull(schema, "Output schema must not be null.");
            if (explanation == null || explanation.isBlank())
                throw new IllegalArgumentException("Output explanation must not be blank.");
            evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "Evidence ids must not be null."));
            relation = Objects.requireNonNull(relation, "Relation must not be null.");
            columns = List.copyOf(Objects.requireNonNull(columns, "Columns must not be null."));
            if (evidenceIds.isEmpty()) throw new IllegalArgumentException("Output must cite evidence.");
        }
    }

    record InferenceResponse(String model, GroundedOutput output, long inputTokens, long outputTokens, Duration duration) {
        public InferenceResponse {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model must not be blank.");
            Objects.requireNonNull(output, "Grounded output must not be null.");
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Token counts must not be negative.");
            if (duration == null || duration.isNegative()) throw new IllegalArgumentException("Duration must not be negative.");
        }
    }
}
