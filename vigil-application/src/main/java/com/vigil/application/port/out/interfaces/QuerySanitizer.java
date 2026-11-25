package com.vigil.application.port.out.interfaces;

import com.vigil.core.domain.SanitizationResult;

/**
 * Sanitizes authorized SQL into a safe, literal-free context. The result is explicit: {@link SanitizationResult.Safe}
 * or {@link SanitizationResult.Blocked}. Implementations are fail-closed — any unforeseen situation (unknown token,
 * internal error, null input) is a {@code Blocked} value, never a {@code Safe} and never a pass-through of the original
 * text. Implementations must never throw for refused content, and must not persist or log the raw SQL.
 */
public interface QuerySanitizer {
    SanitizationResult sanitize(String sql);
}
