package com.vigil.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.core.domain.SanitizationBlockedReason;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class SanitizationResultTest {

    @Test
    void blockedDetailIsDerivedFromTheEnumSoItCannotCarryFreeText() {
        for (var reason : SanitizationBlockedReason.values()) {
            var blocked = new SanitizationResult.Blocked(reason);
            assertEquals(reason.detail(), blocked.detail());
        }
    }

    @Test
    void everyBlockedDetailIsShortFixedTextWithNoLiteral() {
        // The detail is fixed prose per enum (it may name a keyword like "SELECT"); what it must never carry is a
        // value from the input. A literal would be a quoted string or a number, so the detail has neither.
        for (var reason : SanitizationBlockedReason.values()) {
            var detail = new SanitizationResult.Blocked(reason).detail();
            assertAll(
                    () -> assertTrue(detail.length() <= 80, detail),
                    () -> assertFalse(detail.contains("'"), detail),
                    () -> assertFalse(detail.matches(".*\\d.*"), detail));
        }
    }

    @Test
    void blockedRejectsANullReason() {
        assertThrows(NullPointerException.class, () -> new SanitizationResult.Blocked(null));
    }

    @Test
    void safeRejectsBlankContentAndCopiesItsRules() {
        assertThrows(IllegalArgumentException.class,
                () -> new SanitizationResult.Safe("  ", List.of(SanitizationRule.COMMENTS_REMOVED)));

        var rules = new java.util.ArrayList<>(List.of(SanitizationRule.LITERALS_PLACEHOLDERED));
        var safe = new SanitizationResult.Safe("SELECT $1", rules);
        rules.clear();

        assertEquals(List.of(SanitizationRule.LITERALS_PLACEHOLDERED), safe.appliedRules());
    }
}
