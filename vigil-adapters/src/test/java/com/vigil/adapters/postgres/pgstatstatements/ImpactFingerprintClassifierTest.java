package com.vigil.adapters.postgres.pgstatstatements;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.fingerprint.HmacQueryFingerprinter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImpactFingerprintClassifierTest {
    private final HmacQueryFingerprinter fingerprinter =
            new HmacQueryFingerprinter("classifier-key".getBytes(StandardCharsets.UTF_8));

    @Test
    void classifiesASupportedSelectAndKeepsNoRawText() {
        var fingerprint = ImpactFingerprintClassifier.classify(
                "SELECT id FROM orders WHERE customer_id = 7", fingerprinter);

        assertAll(
                () -> assertTrue(fingerprint.isPresent()),
                () -> assertTrue(fingerprint.get().value().matches("hmac-sha256:[0-9a-f]{64}")));
    }

    @Test
    void sameFamilyRegardlessOfLiterals() {
        assertTrue(ImpactFingerprintClassifier.classify("SELECT id FROM orders WHERE customer_id = 7", fingerprinter)
                .equals(ImpactFingerprintClassifier.classify("SELECT id FROM orders WHERE customer_id = 99",
                        fingerprinter)));
    }

    @Test
    void failsClosedToUnclassified() {
        assertAll(
                () -> assertFalse(ImpactFingerprintClassifier.classify(null, fingerprinter).isPresent()),
                () -> assertFalse(ImpactFingerprintClassifier.classify("   ", fingerprinter).isPresent()),
                () -> assertFalse(ImpactFingerprintClassifier.classify("<insufficient privilege>", fingerprinter)
                        .isPresent()),
                // Normalizer-unsupported texts (the adapter's WHERE clause already restricts to SELECT/WITH).
                () -> assertFalse(ImpactFingerprintClassifier.classify("SELECT 1; SELECT 2", fingerprinter)
                        .isPresent()),
                () -> assertFalse(ImpactFingerprintClassifier.classify("SELECT 'unterminated", fingerprinter)
                        .isPresent()));
    }
}
