package com.vigil.application.port.out.interfaces;

import com.vigil.core.domain.QueryFingerprint;
import java.util.Objects;

/**
 * Explicit result of {@link QueryFingerprinter#fingerprint(String)}: a query is either {@link Supported}, carrying the
 * derived {@link QueryFingerprint}, or {@link Unsupported}, carrying the reason it could not be fingerprinted. Callers
 * must handle both branches; there is no exception path and no silent fallback.
 */
public sealed interface FingerprintOutcome permits FingerprintOutcome.Supported, FingerprintOutcome.Unsupported {

    record Supported(QueryFingerprint fingerprint) implements FingerprintOutcome {
        public Supported {
            Objects.requireNonNull(fingerprint, "Fingerprint must not be null.");
        }
    }

    record Unsupported(String reason) implements FingerprintOutcome {
        public Unsupported {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason must not be blank.");
            }
        }
    }
}
