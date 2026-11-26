package com.vigil.application.port.out.interfaces;

import com.vigil.core.domain.QueryFingerprint;
import java.util.Objects;
import java.util.Optional;

/**
 * One pg_stat_statements entry paired with the fingerprint of its query family. The fingerprint is computed inside the
 * adapter from the query text and crosses the boundary already hashed; an empty fingerprint means the entry is
 * UNCLASSIFIED (insufficient privilege, empty text, or unsupported by the normalizer) and must stay out of the
 * ranking. The raw SQL never crosses this boundary.
 */
public record FingerprintedQueryCounters(CumulativeQueryCounters counters, Optional<QueryFingerprint> fingerprint) {
    public FingerprintedQueryCounters {
        Objects.requireNonNull(counters, "Counters must not be null.");
        Objects.requireNonNull(fingerprint, "Fingerprint optional must not be null.");
    }
}
