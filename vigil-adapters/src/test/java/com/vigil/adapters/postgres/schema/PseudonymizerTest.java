package com.vigil.adapters.postgres.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PseudonymizerTest {
    private static final byte[] SALT = bytes(1);
    private final Pseudonymizer pseudonyms = new Pseudonymizer(SALT);

    @Test
    void sameNameYieldsTheSamePseudonymWithinOneSalt() {
        assertEquals(pseudonyms.relation("app", "customers"), pseudonyms.relation("app", "customers"));
        assertEquals(pseudonyms.column("app", "customers", "cpf"), pseudonyms.column("app", "customers", "cpf"));
        assertEquals(pseudonyms.schema("app"), pseudonyms.schema("app"));
        assertEquals(pseudonyms.index("app", "idx"), pseudonyms.index("app", "idx"));
    }

    @Test
    void anotherSaltBreaksCorrelationAcrossCollections() {
        var other = new Pseudonymizer(bytes(2));
        assertNotEquals(pseudonyms.relation("app", "customers"), other.relation("app", "customers"));
        assertNotEquals(pseudonyms.column("app", "customers", "cpf"), other.column("app", "customers", "cpf"));
    }

    @Test
    void scopeOfTheNameIsPartOfThePseudonym() {
        assertNotEquals(pseudonyms.relation("app", "t"), pseudonyms.relation("other", "t"));
        assertNotEquals(pseudonyms.column("app", "t", "c"), pseudonyms.column("app", "u", "c"));
        assertNotEquals(pseudonyms.column("app", "t", "c"), pseudonyms.column("other", "t", "c"));
        assertNotEquals(pseudonyms.index("app", "i"), pseudonyms.index("other", "i"));
    }

    @Test
    void kindIsPartOfThePseudonymAndFieldBoundariesAreUnambiguous() {
        assertNotEquals(pseudonyms.schema("app").value().substring(7), pseudonyms.relation("app", "x").value().substring(9));
        assertNotEquals(pseudonyms.relation("ab", "c"), pseudonyms.relation("a", "bc"));
        assertNotEquals(pseudonyms.column("a", "b", "c"), pseudonyms.column("a", "bc", ""));
    }

    @Test
    void pseudonymsHaveTheExpectedShapeAndNeverContainTheName() {
        String relation = pseudonyms.relation("payroll", "salaries").value();
        assertEquals("relation_".length() + 32, relation.length());
        for (String value : new String[]{relation, pseudonyms.schema("payroll").value(),
                pseudonyms.column("payroll", "salaries", "cpf").value(), pseudonyms.index("payroll", "salaries").value()}) {
            assertFalse(value.contains("payroll") || value.contains("salaries") || value.contains("cpf"), value);
        }
    }

    @Test
    void saltIsCopiedSoLaterMutationDoesNotChangePseudonyms() {
        byte[] salt = bytes(3);
        var local = new Pseudonymizer(salt);
        var before = local.relation("a", "b");
        Arrays.fill(salt, (byte) 9);
        assertEquals(before, local.relation("a", "b"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 15})
    void tooShortSaltIsRejected(int length) {
        assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer(new byte[length]));
        assertThrows(IllegalArgumentException.class, () -> new Pseudonymizer(null));
        new Pseudonymizer(new byte[16]);
    }

    private static byte[] bytes(int seed) {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) seed);
        return salt;
    }
}
