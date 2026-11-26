package com.vigil.adapters.inference;

import com.vigil.application.inference.InferenceOutputValidator;
import com.vigil.application.port.out.interfaces.InferencePort;

import java.util.Objects;

/** Optional Bedrock adapter. Disabled instances make no client invocation. */
public final class BedrockInferenceAdapter implements InferencePort {
    private final BedrockRuntimeClient client;
    private final boolean enabled;

    public BedrockInferenceAdapter(BedrockRuntimeClient client, boolean enabled) {
        this.client = Objects.requireNonNull(client, "Bedrock client must not be null.");
        this.enabled = enabled;
    }

    @Override
    public InferenceResponse infer(InferenceRequest request) {
        Objects.requireNonNull(request, "Inference request must not be null.");
        if (!enabled) throw new IllegalStateException("BEDROCK_DISABLED");
        if (request.context().allowedEvidenceIds().isEmpty()) {
            throw new IllegalArgumentException("INFERENCE_CONTEXT_NOT_APPROVED");
        }
        var response = client.invoke(request);
        new InferenceOutputValidator().validate(request, response.output());
        return response;
    }
}
