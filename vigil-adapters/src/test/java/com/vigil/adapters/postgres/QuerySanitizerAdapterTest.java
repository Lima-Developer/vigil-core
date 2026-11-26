package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.core.domain.SanitizationBlockedReason;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import org.junit.jupiter.api.Test;

class QuerySanitizerAdapterTest {

    private final QuerySanitizerAdapter sanitizer = new QuerySanitizerAdapter();

    @Test
    void simpleSelectWithLiteralsIsSafeWithPlaceholdersAndNoLiteral() {
        var safe = assertInstanceOf(SanitizationResult.Safe.class,
                sanitizer.sanitize("SELECT id FROM orders WHERE customer_id = 10 AND name = 'secret-name'"));

        assertAll(
                () -> assertEquals("SELECT id FROM orders WHERE customer_id = $1 AND name = $2", safe.sanitizedContent()),
                () -> assertFalse(safe.sanitizedContent().contains("secret-name")),
                () -> assertFalse(safe.sanitizedContent().contains("'")),
                () -> assertTrue(safe.appliedRules().contains(SanitizationRule.LITERALS_PLACEHOLDERED)));
    }

    @Test
    void acceptsAlreadyNormalizedTextWithPlaceholders() {
        // Deliberate difference from the EXPLAIN guard: pg_stat_statements text already carries $1.
        var safe = assertInstanceOf(SanitizationResult.Safe.class,
                sanitizer.sanitize("SELECT id FROM orders WHERE customer_id = $1"));

        assertEquals("SELECT id FROM orders WHERE customer_id = $1", safe.sanitizedContent());
    }

    @Test
    void removesLineAndBlockComments() {
        var safe = assertInstanceOf(SanitizationResult.Safe.class, sanitizer.sanitize(
                "SELECT id FROM orders -- secret comment\n WHERE id = 5 /* hidden 99 */"));

        assertAll(
                () -> assertEquals("SELECT id FROM orders WHERE id = $1", safe.sanitizedContent()),
                () -> assertFalse(safe.sanitizedContent().contains("secret")),
                () -> assertFalse(safe.sanitizedContent().contains("99")),
                () -> assertFalse(safe.sanitizedContent().contains("--")),
                () -> assertFalse(safe.sanitizedContent().contains("/*")));
    }

    @Test
    void blocksWithTheCorrectReasonForEachPolicyViolation() {
        assertAll(
                () -> assertEquals(SanitizationBlockedReason.MULTIPLE_STATEMENTS, blockedReason("SELECT 1; SELECT 2")),
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT, blockedReason("INSERT INTO t VALUES (1)")),
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT, blockedReason("UPDATE t SET x = 1")),
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT, blockedReason("DELETE FROM t")),
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT, blockedReason("CREATE TABLE t (id int)")),
                () -> assertEquals(SanitizationBlockedReason.FORBIDDEN_CLAUSE, blockedReason("SELECT * FROM t FOR UPDATE")),
                () -> assertEquals(SanitizationBlockedReason.TOO_LARGE,
                        blockedReason("SELECT " + "a".repeat(9000) + " FROM t")));
    }

    @Test
    void blocksUnterminatedUnknownTokenAndNullInput() {
        assertAll(
                () -> assertInstanceOf(SanitizationResult.Blocked.class, sanitizer.sanitize("SELECT 'abc")),
                () -> assertInstanceOf(SanitizationResult.Blocked.class, sanitizer.sanitize("SELECT id /* open")),
                () -> assertEquals(SanitizationBlockedReason.UNSUPPORTED_SYNTAX, blockedReason("SELECT id ` bad")),
                () -> assertEquals(SanitizationBlockedReason.NULL_INPUT,
                        ((SanitizationResult.Blocked) sanitizer.sanitize(null)).reason()));
    }

    @Test
    void blockedDetailNeverContainsTheSqlOrALiteral() {
        var blocked = (SanitizationResult.Blocked) sanitizer.sanitize(
                "UPDATE customers SET cpf = '12345678900' WHERE name = 'secret-name'");

        assertAll(
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT, blocked.reason()),
                () -> assertFalse(blocked.detail().contains("12345678900")),
                () -> assertFalse(blocked.detail().contains("secret-name")),
                () -> assertFalse(blocked.detail().toLowerCase().contains("update")));
    }

    private SanitizationBlockedReason blockedReason(String sql) {
        return assertInstanceOf(SanitizationResult.Blocked.class, sanitizer.sanitize(sql)).reason();
    }
}
