package com.vigil.core.domain.enums;

/**
 * Auditable lifecycle of an analysis case. A status is a fact established by a
 * named domain command; callers cannot set it directly.
 */
public enum CaseStatus {
    DISCOVERED,
    TRIAGED,
    EVIDENCE_COLLECTED,
    SANITIZED,
    DIAGNOSED,
    HYPOTHESIS_PROPOSED,
    VALIDATING,
    VALIDATED,
    INVALIDATED,
    INCONCLUSIVE,
    AWAITING_HUMAN,
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED,
    ARCHIVED
}
