package com.vigil.adapters.postgres.schema;

import com.vigil.core.domain.SafeCatalogReference;
import com.vigil.core.domain.SafeRelationReference;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 pseudonyms keyed by a salt that lives for one collection: the same name always yields the same
 * pseudonym inside it, and nothing correlates pseudonyms across collections. The salt is never persisted or logged.
 */
public final class Pseudonymizer {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SALT_BYTES = 16;
    private static final int DIGEST_BYTES = 16;

    private final SecretKeySpec key;

    public Pseudonymizer(byte[] salt) {
        if (salt == null || salt.length < MIN_SALT_BYTES) {
            throw new IllegalArgumentException("Salt must have at least " + MIN_SALT_BYTES + " bytes.");
        }
        this.key = new SecretKeySpec(salt.clone(), ALGORITHM);
    }

    public SafeRelationReference relation(String schema, String relation) {
        return new SafeRelationReference("relation_" + digest("relation", schema, relation));
    }

    public SafeCatalogReference schema(String schema) {
        return new SafeCatalogReference("schema_" + digest("schema", schema));
    }

    public SafeCatalogReference column(String schema, String relation, String column) {
        return new SafeCatalogReference("column_" + digest("column", schema, relation, column));
    }

    public SafeCatalogReference index(String schema, String index) {
        return new SafeCatalogReference("index_" + digest("index", schema, index));
    }

    /** Fields are joined by NUL, which a PostgreSQL identifier cannot contain, so the encoding is unambiguous. */
    private String digest(String kind, String... parts) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(kind.getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                mac.update((byte) 0);
                mac.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(mac.doFinal(), 0, DIGEST_BYTES);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.");
        }
    }
}
