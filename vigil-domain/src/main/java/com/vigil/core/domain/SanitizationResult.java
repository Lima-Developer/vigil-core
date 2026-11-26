package com.vigil.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Explicit result of sanitizing authorized SQL into a safe context. Exactly two cases: {@link Safe}, carrying the
 * literal-free sanitized content and the rules that were applied, or {@link Blocked}, carrying only the reason it was
 * refused. There is no exception path for refused content — refusal is a {@code Blocked} value.
 *
 * <p>This is a pure value object: it knows no SQL parser and no crypto. Deriving the result (normalization and policy)
 * lives in an adapter behind the {@code QuerySanitizer} port.
 */
public sealed interface SanitizationResult permits SanitizationResult.Safe, SanitizationResult.Blocked {

    record Safe(String sanitizedContent, List<SanitizationRule> appliedRules) implements SanitizationResult {
        public Safe {
            if (sanitizedContent == null || sanitizedContent.isBlank()) {
                throw new IllegalArgumentException("Sanitized content must not be blank.");
            }
            appliedRules = List.copyOf(Objects.requireNonNull(appliedRules, "Applied rules must not be null."));
        }
    }

    record Blocked(SanitizationBlockedReason reason) implements SanitizationResult {
        public Blocked {
            Objects.requireNonNull(reason, "Reason must not be null.");
        }

        /** Fixed, SQL-free detail derived from the reason; never free text, so it cannot leak SQL or a literal. */
        public String detail() {
            return reason.detail();
        }
    }
}
