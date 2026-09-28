package com.vigil.application.port.out.interfaces;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.core.domain.AnalysisCase;

import java.util.UUID;

public interface AnalysisCaseRepository {

    /**
     * Saves the aggregate atomically. Replaying the same case state for the same id is idempotent;
     * a concurrent divergent update must raise {@link ConcurrentCaseUpdate}.
     */
    void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate;

    /**
     * Returns the case identified by {@code caseId}; callers may retry this lookup without side effects.
     */
    AnalysisCase getById(UUID caseId) throws CaseNotFound;
}
