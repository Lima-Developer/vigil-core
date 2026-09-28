package com.vigil.application.port.out.interfaces;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.core.domain.AnalysisCase;

import java.util.UUID;
import java.util.Optional;

public interface AnalysisCaseRepository {

    /**
     * Saves the aggregate atomically. Replaying the same case state for the same id is idempotent;
     * a concurrent divergent update must raise {@link ConcurrentCaseUpdate}.
     */
    void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate;

    /**
     * Persists a newly discovered case exactly once for an idempotency key.
     * A concurrent request must either expose the previously saved case or raise {@link ConcurrentCaseUpdate}.
     */
    void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) throws ConcurrentCaseUpdate;

    /** Returns the case previously created for a discovery key, without side effects. */
    Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey);

    /**
     * Returns the case identified by {@code caseId}; callers may retry this lookup without side effects.
     */
    AnalysisCase getById(UUID caseId) throws CaseNotFound;
}
