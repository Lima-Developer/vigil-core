package com.vigil.application.inference;

import com.vigil.application.port.out.interfaces.InferencePort;

import java.util.Objects;

/** Validates schema identity and grounding before an output can become an auxiliary domain input. */
public final class InferenceOutputValidator {
    public InferencePort.GroundedOutput validate(InferencePort.InferenceRequest request,
                                                 InferencePort.GroundedOutput output) {
        Objects.requireNonNull(request, "Inference request must not be null.");
        Objects.requireNonNull(output, "Inference output must not be null.");
        if (output.schema() != request.outputSchema()) {
            throw new IllegalArgumentException("INFERENCE_SCHEMA_MISMATCH");
        }
        if (output.explanation().matches("(?is).*\\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|FROM|WHERE)\\b.*")
                || output.explanation().matches("(?s).*[;'\"].*") || output.explanation().matches("(?s).*\\d.*")) {
            throw new IllegalArgumentException("INFERENCE_CONTENT_OUTSIDE_CONTRACT");
        }
        if (!request.context().allowedEvidenceIds().containsAll(output.evidenceIds())) {
            throw new IllegalArgumentException("INFERENCE_EVIDENCE_NOT_ALLOWED");
        }
        output.relation().ifPresent(relation -> {
            if (!request.context().relations().contains(relation)) {
                throw new IllegalArgumentException("INFERENCE_RELATION_NOT_ALLOWED");
            }
        });
        if (!request.context().catalogReferences().containsAll(output.columns())) {
            throw new IllegalArgumentException("INFERENCE_CATALOG_REFERENCE_NOT_ALLOWED");
        }
        return output;
    }
}
