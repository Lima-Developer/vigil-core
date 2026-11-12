package com.vigil.application.port.out.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record WorkspaceTelemetrySample(Instant observedAt, Instant windowStart, String targetId,
        String state, String quality, Long calls, BigDecimal executionMillis, BigDecimal meanExecutionMillis,
        Long rows, Long sharedHits, Long sharedReads, Long tempReads, Long tempWrites,
        Long activeSessions, Long waitingSessions, Long blockedSessions, String reason) {
    public WorkspaceTelemetrySample {
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(targetId);
        Objects.requireNonNull(state);
        Objects.requireNonNull(quality);
        if (!java.util.Set.of("available", "warming_up", "error").contains(state)) {
            throw new IllegalArgumentException("Invalid state");
        }
        if (!java.util.Set.of("measured", "partial", "unknown").contains(quality)) {
            throw new IllegalArgumentException("Invalid quality");
        }
        if ("available".equals(state) != (windowStart != null)) {
            throw new IllegalArgumentException("Only available samples have a window");
        }
        if ("available".equals(state)) {
            requireCounters(calls, executionMillis, rows, sharedHits, sharedReads, tempReads, tempWrites,
                    activeSessions, waitingSessions, blockedSessions);
            if ("unknown".equals(quality)) throw new IllegalArgumentException("Available quality is known");
            if ("measured".equals(quality) && reason != null) {
                throw new IllegalArgumentException("Measured sample has no partial reason");
            }
        } else if (calls != null || executionMillis != null || meanExecutionMillis != null || rows != null
                || sharedHits != null || sharedReads != null || tempReads != null || tempWrites != null) {
            throw new IllegalArgumentException("Unavailable samples cannot contain deltas");
        }
        if ("error".equals(state) && !"unknown".equals(quality)) {
            throw new IllegalArgumentException("Error quality must be unknown");
        }
        if (("partial".equals(quality) || !"available".equals(state)) && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Reason required");
        }
        checkNonNegative(calls, rows, sharedHits, sharedReads, tempReads, tempWrites,
                activeSessions, waitingSessions, blockedSessions);
        if (executionMillis != null && executionMillis.signum() < 0
                || meanExecutionMillis != null && meanExecutionMillis.signum() < 0) {
            throw new IllegalArgumentException("Negative duration");
        }
    }

    private static void requireCounters(Long calls, BigDecimal executionMillis, Long rows, Long sharedHits,
            Long sharedReads, Long tempReads, Long tempWrites, Long activeSessions, Long waitingSessions,
            Long blockedSessions) {
        if (calls == null || executionMillis == null || rows == null || sharedHits == null || sharedReads == null
                || tempReads == null || tempWrites == null || activeSessions == null || waitingSessions == null
                || blockedSessions == null) {
            throw new IllegalArgumentException("Available sample is incomplete");
        }
    }

    private static void checkNonNegative(Long... values) {
        for (Long value : values) {
            if (value != null && value < 0) throw new IllegalArgumentException("Negative counter");
        }
    }
}
