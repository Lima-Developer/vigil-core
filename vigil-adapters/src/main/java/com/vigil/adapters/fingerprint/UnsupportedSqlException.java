package com.vigil.adapters.fingerprint;

/**
 * Internal, fail-closed signal that SQL cannot be reduced to its canonical form. It is translated into a
 * {@code FingerprintOutcome.Unsupported} at the port boundary and never escapes the adapter.
 */
public final class UnsupportedSqlException extends RuntimeException {
    UnsupportedSqlException(String message) {
        super(message);
    }
}
