package com.vigil.application.casework;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.BaselineConfidence;
import com.vigil.core.domain.Candidate;
import com.vigil.core.domain.ImpactAssessment;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.QueryImpactDelta;
import com.vigil.core.domain.SelectionReason;
import com.vigil.core.domain.SourceQueryId;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenCaseFromCandidateTest {
    private static final Instant NOW = Instant.parse("2026-11-24T12:05:00Z");
    private static final String KEY = "candidate-flow";

    private final FakeRepository repository = new FakeRepository();
    private final IdGenerator ids = new SequentialIds();
    private final Clock clock = () -> NOW;
    private final DiscoverObservedQueryUseCase discover = new DiscoverObservedQueryUseCase(repository, clock, ids);
    private final TriageAnalysisCaseUseCase triage = new TriageAnalysisCaseUseCase(repository, clock, ids);

    private static Candidate candidate() {
        var delta = new QueryImpactDelta(56, new BigDecimal("1234"), 500, 0, 0, new BigDecimal("0.41"));
        return new Candidate(new QueryFingerprint("hmac-sha256:" + "a".repeat(64), "v1"),
                List.of(new SourceQueryId("16384")),
                new ImpactAssessment(delta, 1, BaselineConfidence.ABSENT),
                new SelectionReason(SelectionReason.Code.TOP_TOTAL_EXEC_TIME, new BigDecimal("1234"), 56,
                        new BigDecimal("0.41")));
    }

    @Test
    void opensACaseFromACandidateAndRecordsTheGeneratedReasonOnTriageByDefault() {
        var candidate = candidate();
        var window = new ObservationWindow(NOW.minusSeconds(300), NOW);

        var discovered = discover.execute(candidate, TelemetrySource.PG_STAT_STATEMENTS, NOW, window,
                CommandType.SELECT, KEY);
        var replay = discover.execute(candidate, TelemetrySource.PG_STAT_STATEMENTS, NOW, window,
                CommandType.SELECT, KEY);

        // No edit: the suggested triage reason is the generated SelectionReason text.
        triage.execute(new TriageAnalysisCaseCommand(discovered.caseId(), candidate.selectionReason().render(),
                "operator"));
        var stored = repository.getById(discovered.caseId());
        var triagedEvent = stored.events().stream()
                .filter(event -> event.toStatus() == CaseStatus.TRIAGED).findFirst().orElseThrow();

        assertAll(
                () -> assertTrue(discovered.created()),
                () -> assertFalse(replay.created(), "idempotent by discovery key, not fingerprint"),
                () -> assertEquals(discovered.caseId(), replay.caseId()),
                () -> assertEquals(Optional.of(candidate.fingerprint()), stored.queryFingerprint()),
                () -> assertEquals(candidate.selectionReason().render(), triagedEvent.reason()),
                () -> assertTrue(triagedEvent.reason().contains("1234 ms em 56 chamadas")),
                () -> assertFalse(triagedEvent.reason().toLowerCase().contains("select")));
    }

    @Test
    void theOperatorMayEditTheSuggestedReason() {
        var candidate = candidate();
        var window = new ObservationWindow(NOW.minusSeconds(300), NOW);
        var discovered = discover.execute(candidate, TelemetrySource.PG_STAT_STATEMENTS, NOW, window,
                CommandType.SELECT, KEY);

        triage.execute(new TriageAnalysisCaseCommand(discovered.caseId(), "Priorizo manualmente esta família.",
                "operator"));
        var stored = repository.getById(discovered.caseId());
        var triagedEvent = stored.events().stream()
                .filter(event -> event.toStatus() == CaseStatus.TRIAGED).findFirst().orElseThrow();

        assertEquals("Priorizo manualmente esta família.", triagedEvent.reason());
    }

    private static final class SequentialIds implements IdGenerator {
        private long counter = 1;

        @Override public UUID nextId() {
            return new UUID(0, counter++);
        }
    }

    private static final class FakeRepository implements AnalysisCaseRepository {
        private final Map<UUID, AnalysisCase> byId = new HashMap<>();
        private final Map<String, UUID> byKey = new HashMap<>();

        @Override public void save(AnalysisCase analysisCase) {
            byId.put(analysisCase.caseId(), analysisCase);
        }

        @Override public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            byId.put(analysisCase.caseId(), analysisCase);
            byKey.put(idempotencyKey, analysisCase.caseId());
        }

        @Override public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey)).map(byId::get);
        }

        @Override public AnalysisCase getById(UUID caseId) throws CaseNotFound {
            var found = byId.get(caseId);
            if (found == null) throw new CaseNotFound(caseId);
            return found;
        }
    }
}
