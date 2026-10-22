package com.vigil.application.port.out.interfaces;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.core.domain.AnalysisCase;

import java.util.UUID;
import java.util.Optional;

/**
 * Persistence port for the {@link AnalysisCase} aggregate.
 * <p>
 * <b>Version.</b> {@link AnalysisCase#version()} is a numeric optimistic-locking revision, unrelated to
 * {@code DomainEvent.version()}. A new aggregate has version {@code 0}; the first persisted save makes it {@code 1}
 * and each later save that persists a change increments it by one. The in-memory version and the pending domain
 * events are confirmed only after the transaction committed, so a failed save leaves the aggregate retryable.
 * Callers must not drain events with {@code pullEvents()} before saving.
 * <p>
 * <b>Atomicity.</b> The case, its new history entries, evidences and pending domain events are written in a single
 * transaction: all or nothing.
 * <p>
 * <b>Errors.</b> Version conflicts raise {@link ConcurrentCaseUpdate}. Technical failures (I/O, busy database,
 * constraint violations unrelated to concurrency) and corrupt persisted data are unchecked adapter exceptions and
 * are never reported as conflicts or as {@link CaseNotFound}.
 */
public interface AnalysisCaseRepository {

    /**
     * Saves the aggregate atomically. Saving a state already persisted (even through a stale copy) is idempotent:
     * it changes neither the version nor the stored events. A divergent update on top of an outdated version
     * raises {@link ConcurrentCaseUpdate} and leaves the winning state untouched.
     */
    void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate;

    /**
     * Persists a newly discovered case exactly once for an idempotency key. Replaying the same case is idempotent;
     * if a different case already owns the key, raises {@link ConcurrentCaseUpdate} so the caller can fetch the winner
     * with {@link #findByIdempotencyKey(String)}.
     */
    void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) throws ConcurrentCaseUpdate;

    /** Returns the case previously created for a discovery key, without side effects. */
    Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey);

    /**
     * Returns the case identified by {@code caseId}, with no pending domain events; callers may retry this lookup
     * without side effects.
     */
    AnalysisCase getById(UUID caseId) throws CaseNotFound;
}
