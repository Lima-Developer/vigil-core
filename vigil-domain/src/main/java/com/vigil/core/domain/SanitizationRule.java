package com.vigil.core.domain;

/** A normalization rule applied while producing a safe, literal-free context. Names only; never carries any value. */
public enum SanitizationRule {
    COMMENTS_REMOVED,
    LITERALS_PLACEHOLDERED,
    WHITESPACE_COLLAPSED
}
