package com.vigil.adapters.fingerprint;

import com.vigil.application.port.out.interfaces.FingerprintOutcome;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import com.vigil.core.domain.QueryFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives the {@code v1} fingerprint: it normalizes the SQL with {@link SqlCanonicalizer} and keys an HMAC-SHA256 over
 * {@code algorithmVersion + "\n" + canonicalForm}. The resulting value is {@code "hmac-sha256:" + lowercaseHex}.
 *
 * <h2>Why HMAC and not a bare SHA-256</h2>
 * A plain digest of a known query is trivially reversible by dictionary: an attacker who can guess the query (there are
 * only so many query shapes against a given schema) simply hashes candidates until one matches, confirming the query.
 * Keying the digest with an installation secret turns the fingerprint into a token that cannot be brute-forced without
 * the key, while remaining a one-way, non-reversible value (it is never decrypted back into SQL).
 *
 * <h2>Why the key is stable</h2>
 * The key is <strong>stable per installation</strong> so the same query family yields the same fingerprint across
 * collections over time — that temporal stability is what lets Vigil correlate a query family between collections. This
 * is the opposite of {@code com.vigil.adapters.postgres.schema.Pseudonymizer}, whose salt is <em>ephemeral per
 * collection</em> precisely so pseudonyms cannot be correlated across collections. The two keys serve opposite goals
 * and must never be shared. The key comes from configuration; it is never hardcoded.
 */
public final class HmacQueryFingerprinter implements QueryFingerprinter {

    /** The algorithm version of this implementation. It is mixed into the HMAC input so bumping it changes every value. */
    public static final String ALGORITHM_VERSION = "v1";

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String VALUE_PREFIX = "hmac-sha256:";

    private final SqlCanonicalizer canonicalizer = new SqlCanonicalizer();
    private final byte[] key;
    private final String algorithmVersion;

    public HmacQueryFingerprinter(byte[] key) {
        this(key, ALGORITHM_VERSION);
    }

    HmacQueryFingerprinter(byte[] key, String algorithmVersion) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Fingerprint key must not be empty.");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("Algorithm version must not be blank.");
        }
        this.key = key.clone();
        this.algorithmVersion = algorithmVersion;
    }

    @Override
    public FingerprintOutcome fingerprint(String sql) {
        final String canonical;
        try {
            canonical = canonicalizer.canonicalize(sql);
        } catch (UnsupportedSqlException unsupported) {
            return new FingerprintOutcome.Unsupported(unsupported.getMessage());
        }
        String hex = hmacHex(algorithmVersion + "\n" + canonical);
        return new FingerprintOutcome.Supported(new QueryFingerprint(VALUE_PREFIX + hex, algorithmVersion));
    }

    private String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.");
        }
    }
}
