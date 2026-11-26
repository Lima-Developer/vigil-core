package com.vigil.application.casework;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.QuerySanitizer;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.SanitizationBlockedReason;
import com.vigil.core.domain.SanitizationResult;
import com.vigil.core.domain.SanitizationRule;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.PlanNodeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SanitizeCaseUseCaseTest {
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Test
    void safeResultAdvancesTheCaseToSanitizedAndSavesIt() {
        var repository = new FakeRepository(evidenceCollectedCase());
        var useCase = new SanitizeCaseUseCase(repository, sql ->
                new SanitizationResult.Safe("SELECT $1", List.of(SanitizationRule.LITERALS_PLACEHOLDERED)),
                () -> NOW);

        var result = useCase.execute(new SanitizeCaseCommand(CASE_ID, "SELECT id FROM t WHERE x = 1", "actor"));

        assertAll(
                () -> assertInstanceOf(SanitizationResult.Safe.class, result),
                () -> assertEquals(CaseStatus.SANITIZED, repository.stored.status()),
                () -> assertEquals(1, repository.saves));
    }

    @Test
    void blockedResultLeavesTheCaseInPlaceAndDoesNotSave() {
        var repository = new FakeRepository(evidenceCollectedCase());
        var useCase = new SanitizeCaseUseCase(repository, sql ->
                new SanitizationResult.Blocked(SanitizationBlockedReason.NOT_SELECT), () -> NOW);

        var result = useCase.execute(new SanitizeCaseCommand(CASE_ID, "UPDATE t SET x = 1", "actor"));

        assertAll(
                () -> assertEquals(SanitizationBlockedReason.NOT_SELECT,
                        assertInstanceOf(SanitizationResult.Blocked.class, result).reason()),
                () -> assertEquals(CaseStatus.EVIDENCE_COLLECTED, repository.stored.status()),
                () -> assertEquals(0, repository.saves));
    }

    @Test
    void commandRejectsMissingArguments() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new SanitizeCaseCommand(null, "SELECT 1", "actor")),
                () -> assertThrows(NullPointerException.class, () -> new SanitizeCaseCommand(CASE_ID, null, "actor")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SanitizeCaseCommand(CASE_ID, "SELECT 1", " ")));
    }

    private static AnalysisCase evidenceCollectedCase() {
        var analysisCase = AnalysisCase.discover(CASE_ID, NOW);
        analysisCase.triage(new TransitionMetadata("actor", "priorização explícita", NOW));
        var payload = new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN), 100,
                Optional.of(new BigDecimal("12.00")));
        var evidence = new Evidence(UUID.randomUUID(), CASE_ID, payload.type(), EvidenceSource.POSTGRESQL_EXPLAIN,
                NOW, "collector", EvidenceConfidence.HIGH, payload, Optional.empty());
        analysisCase.collectEvidence(evidence, "actor", NOW);
        return analysisCase;
    }

    private static final class FakeRepository implements AnalysisCaseRepository {
        private final AnalysisCase stored;
        private int saves;

        private FakeRepository(AnalysisCase stored) {
            this.stored = stored;
        }

        @Override public void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate {
            saves++;
        }

        @Override public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override public AnalysisCase getById(UUID caseId) throws CaseNotFound {
            if (!stored.caseId().equals(caseId)) throw new CaseNotFound(caseId);
            return stored;
        }
    }
}
