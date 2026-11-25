package com.vigil.application.port.out.interfaces;

/**
 * Derives a {@link com.vigil.core.domain.QueryFingerprint} from authorized SQL. The result is explicit: either a
 * supported fingerprint or an unsupported outcome carrying a human-readable reason.
 *
 * <p>Implementations must be fail-closed. They never throw a generic exception on SQL they cannot understand and they
 * never fall back to hashing the raw SQL: an unparseable query is an {@link FingerprintOutcome.Unsupported} value, not
 * a silently degraded fingerprint. The raw SQL and any intermediate canonical form must not be persisted or logged.
 */
public interface QueryFingerprinter {
    FingerprintOutcome fingerprint(String sql);
}
