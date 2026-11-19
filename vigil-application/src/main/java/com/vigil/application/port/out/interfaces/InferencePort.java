package com.vigil.application.port.out.interfaces;

import java.time.Duration;

/** Provider-independent, bounded text inference port. */
public interface InferencePort {
    InferenceResponse infer(InferenceRequest request);

    record InferenceRequest(String model, String prompt, Duration timeout) {
        public InferenceRequest {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model must not be blank.");
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt must not be blank.");
            if (timeout == null || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("Timeout must be positive.");
        }
    }

    record InferenceResponse(String model, String content, long inputTokens, long outputTokens, Duration duration) {
        public InferenceResponse {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model must not be blank.");
            if (content == null || content.isBlank()) throw new IllegalArgumentException("Content must not be blank.");
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Token counts must not be negative.");
            if (duration == null || duration.isNegative()) throw new IllegalArgumentException("Duration must not be negative.");
        }
    }
}
