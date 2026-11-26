package com.vigil.adapters.inference;

import com.vigil.application.port.out.interfaces.InferencePort;

/** Narrow seam for an optional AWS SDK client; production wiring is deliberately absent by default. */
public interface BedrockRuntimeClient {
    InferencePort.InferenceResponse invoke(InferencePort.InferenceRequest request);
}
