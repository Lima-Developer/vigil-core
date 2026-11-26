package com.vigil.adapters.postgres.pgstatstatements;

import com.vigil.application.port.out.interfaces.FingerprintOutcome;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import com.vigil.core.domain.QueryFingerprint;
import java.util.Optional;

/**
 * Fail-closed classification of a pg_stat_statements query text into its family fingerprint, or UNCLASSIFIED (an empty
 * optional). Pure and DB-free so it can be unit-tested: {@code "<insufficient privilege>"} (no {@code pg_read_all_stats}),
 * blank text, or text the normalizer rejects all yield UNCLASSIFIED. The raw text is only read here and never escapes.
 */
final class ImpactFingerprintClassifier {
    private static final String INSUFFICIENT_PRIVILEGE = "<insufficient privilege>";

    private ImpactFingerprintClassifier() {
    }

    static Optional<QueryFingerprint> classify(String text, QueryFingerprinter fingerprinter) {
        if (text == null || text.isBlank() || text.strip().equals(INSUFFICIENT_PRIVILEGE)) {
            return Optional.empty();
        }
        return fingerprinter.fingerprint(text) instanceof FingerprintOutcome.Supported supported
                ? Optional.of(supported.fingerprint())
                : Optional.empty();
    }
}
