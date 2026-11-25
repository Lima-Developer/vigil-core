package com.vigil.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, pure selection of query families by <strong>absolute impact</strong> (total execution time), never
 * by the number of calls. A family is eligible only if it clears two thresholds — a minimum share of the workspace
 * execution time AND a minimum total execution time in the interval. The number of calls alone never makes a family
 * eligible (criterion 6). Ties break stably: total execution time desc, calls desc, fingerprint asc. The same input
 * always yields the same output, and an empty result is valid (no family above the thresholds).
 */
public final class ImpactSelectionPolicy {
    /** Documented defaults: share ≥ 5%, total execution time ≥ 100 ms, top 5 families. */
    public static final BigDecimal DEFAULT_MINIMUM_SHARE = new BigDecimal("0.05");
    public static final BigDecimal DEFAULT_MINIMUM_TOTAL_EXECUTION_MILLIS = new BigDecimal("100");
    public static final int DEFAULT_TOP_N = 5;

    private static final int SHARE_SCALE = 6;

    private final BigDecimal minimumShare;
    private final BigDecimal minimumTotalExecutionMillis;
    private final int topN;

    public ImpactSelectionPolicy() {
        this(DEFAULT_MINIMUM_SHARE, DEFAULT_MINIMUM_TOTAL_EXECUTION_MILLIS, DEFAULT_TOP_N);
    }

    public ImpactSelectionPolicy(BigDecimal minimumShare, BigDecimal minimumTotalExecutionMillis, int topN) {
        this.minimumShare = Objects.requireNonNull(minimumShare, "Minimum share must not be null.");
        this.minimumTotalExecutionMillis =
                Objects.requireNonNull(minimumTotalExecutionMillis, "Minimum total execution time must not be null.");
        if (minimumShare.signum() < 0 || minimumShare.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Minimum share must be between 0 and 1.");
        }
        if (minimumTotalExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Minimum total execution time must not be negative.");
        }
        if (topN < 1) {
            throw new IllegalArgumentException("Top N must be at least 1.");
        }
        this.topN = topN;
    }

    /**
     * @param families the per-family aggregates of the interval
     * @param workspaceTotalExecutionMillis the total execution time of the whole workspace in the same interval
     *                                       (classified and unclassified), used as the share denominator
     * @param unclassifiedCount how many keys were dropped because their SQL could not be fingerprinted
     */
    public ImpactSelectionResult select(List<FamilyImpact> families, BigDecimal workspaceTotalExecutionMillis,
                                        int unclassifiedCount) {
        Objects.requireNonNull(families, "Families must not be null.");
        Objects.requireNonNull(workspaceTotalExecutionMillis, "Workspace total must not be null.");

        var eligible = new ArrayList<Scored>();
        for (FamilyImpact family : families) {
            BigDecimal share = shareOf(family.totalExecutionMillis(), workspaceTotalExecutionMillis);
            boolean clearsShare = share.compareTo(minimumShare) >= 0;
            boolean clearsTime = family.totalExecutionMillis().compareTo(minimumTotalExecutionMillis) >= 0;
            if (clearsShare && clearsTime) {
                eligible.add(new Scored(family, share));
            }
        }

        eligible.sort(Comparator
                .comparing((Scored scored) -> scored.family().totalExecutionMillis()).reversed()
                .thenComparing(Comparator.comparingLong((Scored scored) -> scored.family().calls()).reversed())
                .thenComparing(scored -> scored.family().fingerprint().value()));

        var candidates = new ArrayList<Candidate>();
        for (int index = 0; index < eligible.size() && index < topN; index++) {
            Scored scored = eligible.get(index);
            FamilyImpact family = scored.family();
            var delta = new QueryImpactDelta(family.calls(), family.totalExecutionMillis(), family.rows(),
                    family.sharedBlocksHit(), family.sharedBlocksRead(), scored.share());
            var assessment = new ImpactAssessment(delta, index + 1, BaselineConfidence.ABSENT);
            var reason = new SelectionReason(SelectionReason.Code.TOP_TOTAL_EXEC_TIME,
                    family.totalExecutionMillis(), family.calls(), scored.share());
            candidates.add(new Candidate(family.fingerprint(), family.sourceQueryIds(), assessment, reason));
        }

        return candidates.isEmpty()
                ? ImpactSelectionResult.none(unclassifiedCount)
                : ImpactSelectionResult.selected(List.copyOf(candidates), unclassifiedCount);
    }

    private static BigDecimal shareOf(BigDecimal familyTotal, BigDecimal workspaceTotal) {
        if (workspaceTotal.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal share = familyTotal.divide(workspaceTotal, SHARE_SCALE, RoundingMode.HALF_UP);
        return share.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : share;
    }

    private record Scored(FamilyImpact family, BigDecimal share) { }
}
