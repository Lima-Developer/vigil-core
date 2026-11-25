package com.vigil.core.domain;

import java.util.regex.Pattern;

/**
 * Opaque identifier of a query family, used only for grouping and correlation. It is not encrypted SQL and does not
 * allow reconstructing the query. The value is a versioned token {@code <algorithm-prefix>:<64 lowercase hex>}, where
 * the prefix names the derivation (for example {@code hmac-sha256}) and the hex is the digest. This record stays a
 * pure value object: it validates the shape of a fingerprint but never computes one — derivation (normalization and
 * HMAC) lives in an adapter behind a port, so the domain knows no crypto or SQL parser.
 */
public record QueryFingerprint (
   String value,
   String algorithmVersion
) {
    /** {@code <algorithm-prefix>:<64 lowercase hex>}; the prefix is lowercase alphanumerics and hyphens. */
    private static final Pattern VALUE_FORMAT = Pattern.compile("[a-z0-9-]+:[0-9a-f]{64}");

    public QueryFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value must not be blank.");
        }

        if (!VALUE_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Value must be '<algorithm-prefix>:<64 lowercase hex>'.");
        }

        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("Algorithm version must not be blank.");
        }
    }
}
