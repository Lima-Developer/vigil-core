package com.vigil.adapters.postgres;

import com.vigil.adapters.fingerprint.SqlCanonicalizer;
import com.vigil.adapters.fingerprint.UnsupportedSqlException;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.port.out.interfaces.QuerySanitizer;
import com.vigil.core.domain.SanitizationBlockedReason;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import java.util.List;

/**
 * Fail-closed sanitizer that reuses, without duplicating, the two components Vigil already trusts:
 * <ol>
 *   <li>the {@link PostgresSelectStatementGuard} policy (single statement, SELECT-only, forbidden clauses, maximum
 *       length) — a rejection becomes {@link SanitizationResult.Blocked} with the same reason;</li>
 *   <li>the fingerprint {@link SqlCanonicalizer} to remove comments and replace every literal with a placeholder — an
 *       unsupported statement becomes {@code Blocked}.</li>
 * </ol>
 *
 * <p><strong>Deliberate difference from the EXPLAIN guard:</strong> the sanitizer runs the guard with
 * {@code placeholdersAllowed = true}, because the text it receives (from pg_stat_statements) is already normalized and
 * carries placeholders ({@code $1}). The EXPLAIN path keeps rejecting placeholders, since there are no binds to plan.
 *
 * <p>Any unforeseen situation (unknown token, internal error, null input) yields {@code Blocked}; the original SQL is
 * never returned or logged, and the sanitized content is the literal-free canonical form.
 */
public final class QuerySanitizerAdapter implements QuerySanitizer {
    private static final List<SanitizationRule> RULES = List.of(
            SanitizationRule.COMMENTS_REMOVED, SanitizationRule.LITERALS_PLACEHOLDERED,
            SanitizationRule.WHITESPACE_COLLAPSED);

    private final SqlCanonicalizer canonicalizer = new SqlCanonicalizer();

    @Override
    public SanitizationResult sanitize(String sql) {
        if (sql == null) {
            return new SanitizationResult.Blocked(SanitizationBlockedReason.NULL_INPUT);
        }
        try {
            // Policy first: classify TOO_LARGE / MULTIPLE_STATEMENTS / NOT_SELECT / FORBIDDEN_CLAUSE / EMPTY.
            PostgresSelectStatementGuard.scan(sql, true);
            // Then strip comments and replace literals; the canonical form is the safe, literal-free content.
            String canonical = canonicalizer.canonicalize(sql);
            return new SanitizationResult.Safe(canonical, RULES);
        } catch (PlanCollectionRejected rejected) {
            return new SanitizationResult.Blocked(map(rejected));
        } catch (UnsupportedSqlException unsupported) {
            return new SanitizationResult.Blocked(SanitizationBlockedReason.UNSUPPORTED_SYNTAX);
        } catch (RuntimeException unexpected) {
            return new SanitizationResult.Blocked(SanitizationBlockedReason.INTERNAL_ERROR);
        }
    }

    private static SanitizationBlockedReason map(PlanCollectionRejected rejected) {
        return switch (rejected.reason()) {
            case EMPTY -> SanitizationBlockedReason.EMPTY;
            case TOO_LARGE -> SanitizationBlockedReason.TOO_LARGE;
            case MULTIPLE_STATEMENTS -> SanitizationBlockedReason.MULTIPLE_STATEMENTS;
            case NOT_SELECT -> SanitizationBlockedReason.NOT_SELECT;
            case FORBIDDEN_CLAUSE -> SanitizationBlockedReason.FORBIDDEN_CLAUSE;
            // The remaining reasons belong to the EXPLAIN collection path (placeholders, timeouts, plan/target
            // problems) and cannot arise from this static scan; treat defensively as fail-closed.
            case PLACEHOLDERS_UNSUPPORTED, TIMEOUT, PLAN_UNREADABLE, TARGET_UNAVAILABLE ->
                    SanitizationBlockedReason.UNSUPPORTED_SYNTAX;
        };
    }
}
