package com.vigil.application.port.out.interfaces;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.core.domain.AnalysisCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationPortsTest {

    @Test
    void shouldAllowAFakeRepositoryToSaveAndRetrieveACase() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-09-28T12:00:00Z")
        );

        repository.save(analysisCase);
        repository.save(analysisCase);

        assertSame(analysisCase, repository.getById(analysisCase.caseId()));
    }

    @Test
    void shouldExposeAnExplicitNotFoundError() {
        var repository = new FakeAnalysisCaseRepository();

        assertThrows(
                CaseNotFound.class,
                () -> repository.getById(UUID.fromString("99999999-9999-9999-9999-999999999999"))
        );
    }

    private static final class FakeAnalysisCaseRepository implements AnalysisCaseRepository {
        private final Map<UUID, AnalysisCase> cases = new HashMap<>();

        @Override
        public void save(AnalysisCase analysisCase) {
            cases.put(analysisCase.caseId(), analysisCase);
        }

        @Override
        public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            save(analysisCase);
        }

        @Override
        public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public AnalysisCase getById(UUID caseId) {
            var analysisCase = cases.get(caseId);
            if (analysisCase == null) {
                throw new CaseNotFound(caseId);
            }
            return analysisCase;
        }
    }
}
