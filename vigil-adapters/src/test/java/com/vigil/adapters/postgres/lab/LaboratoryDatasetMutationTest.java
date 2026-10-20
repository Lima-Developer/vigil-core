package com.vigil.adapters.postgres.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Pins the generated content of {@link LaboratoryDataset}, so that changes to seed derivation, generation order, vocabulary
 * or the hashed format are visible. The golden values are snapshots of the current generator: changing the generator on
 * purpose requires updating them together with {@code docs/architecture/postgres-laboratory.md}.
 */
class LaboratoryDatasetMutationTest {

    private static final String GOLDEN_SMALL_SEED_42 = "835ed8b44e12a631588d32f5d3979afc0eea07baffc41cc8afaac2f032eae800";
    private static final String GOLDEN_STANDARD_SEED_42 = "17465c708ebfc23ac21248fc399d25a34486e0278c626d72f890d4d27d82c672";

    @Test
    void smallDatasetContentMatchesGolden() {
        assertEquals(GOLDEN_SMALL_SEED_42, new LaboratoryDataset(42, 200, 3_000).sha256());
    }

    @Test
    void standardDatasetContentMatchesGolden() {
        assertEquals(GOLDEN_STANDARD_SEED_42, LaboratoryDataset.standard().sha256());
    }

    @Test
    void hashCoversEveryCustomerAndOrderRow() {
        LaboratoryDataset dataset = new LaboratoryDataset(42, 200, 3_000);
        MessageDigest digest = sha256();
        dataset.forEachCustomer(row -> update(digest,
                "C|" + row.id() + "|" + row.region() + "|" + row.segment() + "|" + row.createdAt()));
        dataset.forEachOrder(row -> update(digest,
                "O|" + row.id() + "|" + row.customerId() + "|" + row.status() + "|" + row.totalCents()
                        + "|" + row.placedAt()));

        assertEquals(HexFormat.of().formatHex(digest.digest()), dataset.sha256());
    }

    private static void update(MessageDigest digest, String line) {
        digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
