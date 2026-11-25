package com.vigil.core.domain;

/**
 * How much historical baseline backs an impact assessment. In the current scope Vigil selects by absolute impact and
 * has no persisted baseline, so assessments are {@link #ABSENT}; the other values exist for the ubiquitous language
 * and future baseline work (regression against baseline is out of scope).
 */
public enum BaselineConfidence {
    ABSENT,
    ESTABLISHING,
    ESTABLISHED
}
