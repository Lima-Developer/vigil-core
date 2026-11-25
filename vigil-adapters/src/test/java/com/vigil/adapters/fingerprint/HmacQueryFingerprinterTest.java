package com.vigil.adapters.fingerprint;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.port.out.interfaces.FingerprintOutcome;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HmacQueryFingerprinterTest {

    private static final byte[] KEY = "stable-installation-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "another-installation-key".getBytes(StandardCharsets.UTF_8);

    private final QueryFingerprinter fingerprinter = new HmacQueryFingerprinter(KEY);

    @Test
    void sameStructureWithDifferentLiteralsSharesTheFingerprint() {
        assertEquals(
                value("SELECT * FROM orders WHERE customer_id = 10"),
                value("SELECT * FROM orders WHERE customer_id = 55"));
    }

    @Test
    void differentStructureProducesDifferentFingerprints() {
        var base = value("SELECT * FROM orders WHERE customer_id = 10");

        assertAll(
                () -> assertNotEquals(base, value("SELECT * FROM orders WHERE product_id = 10")),
                () -> assertNotEquals(base, value("SELECT * FROM orders WHERE customer_id > 10")),
                () -> assertNotEquals(base, value("SELECT * FROM invoices WHERE customer_id = 10")));
    }

    @Test
    void whitespaceCommentsAndKeywordCaseDoNotChangeTheFingerprint() {
        assertEquals(
                value("SELECT * FROM orders WHERE customer_id = 10"),
                value("select *\n  from orders  -- note\n where customer_id = 999 /* x */"));
    }

    @Test
    void stringEscapeEStringAndDollarQuoteAllBecomePlaceholders() {
        var reference = value("SELECT name FROM customer WHERE note = 'plain'");

        assertAll(
                () -> assertEquals(reference, value("SELECT name FROM customer WHERE note = 'O''Brien'")),
                () -> assertEquals(reference, value("SELECT name FROM customer WHERE note = E'line\\nbreak'")),
                () -> assertEquals(reference, value("SELECT name FROM customer WHERE note = $$body$$")),
                () -> assertEquals(reference, value("SELECT name FROM customer WHERE note = $tag$body$tag$")));
    }

    @Test
    void inListArityAndLimitValueDoNotChangeTheFingerprint() {
        assertAll(
                () -> assertEquals(
                        value("SELECT a FROM t WHERE a IN (9)"),
                        value("SELECT a FROM t WHERE a IN (1, 2, 3)")),
                () -> assertEquals(
                        value("SELECT a FROM t LIMIT 50"),
                        value("SELECT a FROM t LIMIT 10")));
    }

    @Test
    void quotedIdentifierCaseChangesTheFingerprint() {
        assertNotEquals(
                value("SELECT a FROM \"Orders\""),
                value("SELECT a FROM orders"));
    }

    @Test
    void differentKeysDifferButTheSameKeyIsDeterministic() {
        var sql = "SELECT * FROM orders WHERE customer_id = 10";
        var withOtherKey = new HmacQueryFingerprinter(OTHER_KEY);

        assertAll(
                () -> assertEquals(value(sql), value(sql)),
                () -> assertNotEquals(value(sql), supported(withOtherKey.fingerprint(sql)).value()));
    }

    @Test
    void changingTheAlgorithmVersionChangesTheFingerprint() {
        var sql = "SELECT * FROM orders WHERE customer_id = 10";
        var v2 = new HmacQueryFingerprinter(KEY, "v2");

        var v1Outcome = supported(fingerprinter.fingerprint(sql));
        var v2Outcome = supported(v2.fingerprint(sql));

        assertAll(
                () -> assertNotEquals(v1Outcome.value(), v2Outcome.value()),
                () -> assertEquals("v1", v1Outcome.algorithmVersion()),
                () -> assertEquals("v2", v2Outcome.algorithmVersion()));
    }

    @Test
    void failsClosedWithAReasonInsteadOfHashingRawSql() {
        assertAll(
                () -> assertReason("Mais de uma instrução não é suportada.", "SELECT 1; SELECT 2"),
                () -> assertReason("String não terminada.", "SELECT * FROM t WHERE a = 'abc"),
                () -> assertReason("Comentário não terminado.", "SELECT * FROM t /* open"));
    }

    @Test
    void producesTheVersionedValueShapeWithoutAnyLiteral() {
        var outcome = supported(fingerprinter.fingerprint(
                "SELECT * FROM customer WHERE cpf = '12345678900' AND name = 'secret-name'"));

        assertAll(
                () -> assertTrue(outcome.value().matches("hmac-sha256:[0-9a-f]{64}"), outcome.value()),
                () -> assertEquals("v1", outcome.algorithmVersion()),
                () -> assertTrue(!outcome.value().contains("12345678900") && !outcome.value().contains("secret-name")));
    }

    private String value(String sql) {
        return supported(fingerprinter.fingerprint(sql)).value();
    }

    private static com.vigil.core.domain.QueryFingerprint supported(FingerprintOutcome outcome) {
        return assertInstanceOf(FingerprintOutcome.Supported.class, outcome).fingerprint();
    }

    private void assertReason(String expectedReason, String sql) {
        var outcome = assertInstanceOf(FingerprintOutcome.Unsupported.class, fingerprinter.fingerprint(sql));
        assertEquals(expectedReason, outcome.reason());
    }
}
