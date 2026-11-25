package com.vigil.adapters.fingerprint;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** White-box checks of the v1 canonical form: structure is kept, every literal becomes a placeholder, fail-closed. */
class SqlCanonicalizerTest {

    private final SqlCanonicalizer canonicalizer = new SqlCanonicalizer();

    @Test
    void replacesEveryValueWithAPositionalPlaceholderInOrder() {
        assertEquals("SELECT * FROM orders WHERE customer_id = $1",
                canonicalizer.canonicalize("SELECT * FROM orders WHERE customer_id = 10"));
        assertEquals("SELECT * FROM orders WHERE a = $1 AND b = $2",
                canonicalizer.canonicalize("select * from orders where a = 1 and b = 'x'"));
    }

    @Test
    void ignoresWhitespaceCommentsAndKeywordCase() {
        var canonical = canonicalizer.canonicalize(
                "select *\n  from orders   -- inline comment\n where customer_id = 10 /* block\n comment */");

        assertEquals("SELECT * FROM orders WHERE customer_id = $1", canonical);
    }

    @Test
    void removesNestedBlockComments() {
        assertEquals("SELECT $1",
                canonicalizer.canonicalize("SELECT /* outer /* inner */ still */ 1"));
    }

    @Test
    void collapsesConstantInListsToASinglePlaceholder() {
        assertEquals(
                canonicalizer.canonicalize("SELECT a FROM t WHERE a IN (9)"),
                canonicalizer.canonicalize("SELECT a FROM t WHERE a IN (1, 2, 3)"));
        assertEquals("SELECT a FROM t WHERE a IN ( $1 )",
                canonicalizer.canonicalize("SELECT a FROM t WHERE a IN (1,2,3)"));
    }

    @Test
    void leavesInListWithNonConstantsUntouched() {
        assertEquals("SELECT a FROM t WHERE a IN ( b , $1 )",
                canonicalizer.canonicalize("SELECT a FROM t WHERE a IN (b, 2)"));
    }

    @Test
    void placeholdersLimitAndOffsetNumbers() {
        assertEquals(
                canonicalizer.canonicalize("SELECT a FROM t LIMIT 50 OFFSET 10"),
                canonicalizer.canonicalize("SELECT a FROM t LIMIT 10 OFFSET 0"));
        assertEquals("SELECT a FROM t LIMIT $1 OFFSET $2",
                canonicalizer.canonicalize("SELECT a FROM t LIMIT 10 OFFSET 0"));
    }

    @Test
    void placeholdersEveryKindOfLiteralButKeepsNull() {
        assertAll(
                () -> assertEquals("SELECT $1", canonicalizer.canonicalize("SELECT 'O''Brien'")),
                () -> assertEquals("SELECT $1", canonicalizer.canonicalize("SELECT E'line\\nbreak'")),
                () -> assertEquals("SELECT $1", canonicalizer.canonicalize("SELECT $$a$$")),
                () -> assertEquals("SELECT $1", canonicalizer.canonicalize("SELECT $tag$a$tag$")),
                () -> assertEquals("SELECT $1", canonicalizer.canonicalize("SELECT 1.5e10")),
                () -> assertEquals("SELECT DATE $1", canonicalizer.canonicalize("SELECT DATE '2024-01-01'")),
                () -> assertEquals("SELECT $1 , $2 , NULL", canonicalizer.canonicalize("SELECT TRUE, false, null")));
    }

    @Test
    void renumbersExistingParametersIntoTheSameSequence() {
        assertEquals("SELECT $1 FROM t WHERE a = $2 AND b = $3",
                canonicalizer.canonicalize("SELECT 5 FROM t WHERE a = $1 AND b = $2"));
    }

    @Test
    void foldsUnquotedIdentifiersButKeepsQuotedCase() {
        assertAll(
                () -> assertEquals("SELECT a FROM orders", canonicalizer.canonicalize("SELECT a FROM Orders")),
                () -> assertEquals("SELECT a FROM \"Orders\"", canonicalizer.canonicalize("SELECT a FROM \"Orders\"")),
                () -> assertFalse(canonicalizer.canonicalize("SELECT a FROM \"Orders\"")
                        .equals(canonicalizer.canonicalize("SELECT a FROM orders"))));
    }

    @Test
    void neverLeavesAnyLiteralInTheCanonicalForm() {
        var canonical = canonicalizer.canonicalize(
                "SELECT * FROM customer WHERE name = 'secret-name' AND cpf = '12345678900' "
                        + "AND status IN ('A','B') AND created = DATE '2024-05-01' LIMIT 25");

        assertAll(
                () -> assertFalse(canonical.contains("secret-name")),
                () -> assertFalse(canonical.contains("12345678900")),
                () -> assertFalse(canonical.contains("2024-05-01")),
                () -> assertFalse(canonical.matches(".*\\b25\\b.*")),
                () -> assertFalse(canonical.contains("'")));
    }

    @Test
    void failsClosedOnUnsupportedSql() {
        assertAll(
                () -> assertEquals("Mais de uma instrução não é suportada.",
                        unsupportedReason("SELECT 1; SELECT 2")),
                () -> assertEquals("String não terminada.", unsupportedReason("SELECT 'abc")),
                () -> assertEquals("Comentário não terminado.", unsupportedReason("SELECT /* abc")),
                () -> assertEquals("Dollar-quote não terminado.", unsupportedReason("SELECT $$abc")),
                () -> assertEquals("Instrução vazia.", unsupportedReason("   -- only a comment\n")),
                () -> assertEquals("Token não reconhecido.", unsupportedReason("SELECT `a`")));
    }

    private String unsupportedReason(String sql) {
        return assertThrows(UnsupportedSqlException.class, () -> canonicalizer.canonicalize(sql)).getMessage();
    }
}
