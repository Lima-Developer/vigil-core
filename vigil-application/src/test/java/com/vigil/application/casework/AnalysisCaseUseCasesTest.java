package com.vigil.application.casework;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.AnalysisCaseDiscovered;
import com.vigil.core.domain.QueryTriaged;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisCaseUseCasesTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID NEW_CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXISTING_CASE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void shouldCreateAndPersistACaseForTheObservedQuery() {
        var repository = new FakeAnalysisCaseRepository();
        var idGenerator = new FixedIdGenerator(NEW_CASE_ID, EVENT_ID);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), idGenerator);

        var result = useCase.execute(discoveryCommand("observation-1"));
        var savedCase = repository.getById(NEW_CASE_ID);

        assertAll(
                () -> assertEquals(NEW_CASE_ID, result.caseId()),
                () -> assertTrue(result.created()),
                () -> assertEquals(NOW, savedCase.createdAt()),
                () -> assertEquals(observedQuery().fingerprint(), savedCase.queryFingerprint().orElseThrow()),
                () -> assertEquals(2, idGenerator.calls()),
                () -> assertEquals(
                        List.of(new AnalysisCaseDiscovered(EVENT_ID, NEW_CASE_ID, "vigil-system", NOW, "1")),
                        savedCase.pendingEvents())
        );
    }

    @Test
    void shouldReturnTheExistingCaseWhenDiscoveryIsRetriedWithTheSameKey() {
        var repository = new FakeAnalysisCaseRepository();
        var idGenerator = new FixedIdGenerator(NEW_CASE_ID, EVENT_ID);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), idGenerator);

        var first = useCase.execute(discoveryCommand("observation-1"));
        var retry = useCase.execute(discoveryCommand("observation-1"));

        assertAll(
                () -> assertTrue(first.created()),
                () -> assertEquals(first.caseId(), retry.caseId()),
                () -> assertFalse(retry.created()),
                () -> assertEquals(1, repository.discoveredSaves()),
                () -> assertEquals(2, idGenerator.calls())
        );
    }

    @Test
    void shouldReturnTheConcurrentWinnerWhenDiscoveryConflicts() {
        var repository = new FakeAnalysisCaseRepository();
        var winner = AnalysisCase.discover(EXISTING_CASE_ID, NOW);
        repository.failNextDiscoveryWithWinner("observation-1", winner);
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), new FixedIdGenerator(NEW_CASE_ID, EVENT_ID));

        var result = useCase.execute(discoveryCommand("observation-1"));

        assertAll(
                () -> assertEquals(EXISTING_CASE_ID, result.caseId()),
                () -> assertFalse(result.created()),
                () -> assertEquals(0, repository.discoveredSaves())
        );
    }

    @Test
    void shouldPropagateAConflictWhenNoConcurrentWinnerCanBeRead() {
        var repository = new FakeAnalysisCaseRepository();
        repository.failNextDiscoveryWithoutWinner();
        var useCase = new DiscoverObservedQueryUseCase(repository, fixedClock(), new FixedIdGenerator(NEW_CASE_ID, EVENT_ID));

        assertThrows(ConcurrentCaseUpdate.class, () -> useCase.execute(discoveryCommand("observation-1")));
    }

    @Test
    void shouldTriageAnExistingDiscoveredCaseAndPersistIt() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        repository.save(analysisCase);
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"));

        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(2, repository.saves()),
                () -> assertEquals(NOW, analysisCase.events().getFirst().occuredAt()),
                () -> assertEquals(
                        List.of(new QueryTriaged(EVENT_ID, NEW_CASE_ID, "operator", NOW, "1")),
                        analysisCase.pendingEvents())
        );
    }

    @Test
    void shouldExposeNotFoundWhenTriagingAnUnknownCase() {
        var repository = new FakeAnalysisCaseRepository();
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        assertThrows(
                CaseNotFound.class,
                () -> useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"))
        );
        assertEquals(0, repository.saves());
    }

    @Test
    void shouldMapInvalidDomainTransitionsWithoutPersistingTheCase() {
        var repository = new FakeAnalysisCaseRepository();
        var analysisCase = AnalysisCase.discover(NEW_CASE_ID, NOW);
        analysisCase.triage("Already triaged.", "operator", NOW);
        repository.save(analysisCase);
        var useCase = new TriageAnalysisCaseUseCase(repository, fixedClock(), new FixedIdGenerator(EVENT_ID));

        var exception = assertThrows(
                InvalidAnalysisCaseTransition.class,
                () -> useCase.execute(new TriageAnalysisCaseCommand(NEW_CASE_ID, "Impacto alto.", "operator"))
        );

        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(1, repository.saves()),
                () -> assertTrue(exception.getCause() instanceof com.vigil.core.exception.InvalidCaseTransitionException)
        );
    }

    private static DiscoverObservedQueryCommand discoveryCommand(String idempotencyKey) {
        return new DiscoverObservedQueryCommand(observedQuery(), idempotencyKey);
    }

    private static ObservedQuery observedQuery() {
        return new ObservedQuery(
                new QueryFingerprint("sha256:observed-query", "v1"),
                TelemetrySource.PG_STAT_STATEMENTS,
                NOW,
                Optional.empty(),
                CommandType.SELECT,
                new ObservationWindow(NOW.minusSeconds(60), NOW),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 100)
        );
    }

    private static Clock fixedClock() {
        return () -> NOW;
    }

    private static final class FixedIdGenerator implements IdGenerator {
        private final UUID[] ids;
        private int calls;

        private FixedIdGenerator(UUID... ids) {
            this.ids = ids;
        }

        @Override
        public UUID nextId() {
            return ids[calls++];
        }

        private int calls() {
            return calls;
        }
    }

    private static final class FakeAnalysisCaseRepository implements AnalysisCaseRepository {
        private final Map<UUID, AnalysisCase> cases = new HashMap<>();
        private final Map<String, AnalysisCase> discoveries = new HashMap<>();
        private String conflictingKey;
        private AnalysisCase winnerAfterConflict;
        private boolean conflictWithoutWinner;
        private int saves;
        private int discoveredSaves;

        @Override
        public void save(AnalysisCase analysisCase) {
            cases.put(analysisCase.caseId(), analysisCase);
            saves++;
        }

        @Override
        public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) {
            if (conflictWithoutWinner || idempotencyKey.equals(conflictingKey)) {
                if (winnerAfterConflict != null) {
                    cases.put(winnerAfterConflict.caseId(), winnerAfterConflict);
                    discoveries.put(idempotencyKey, winnerAfterConflict);
                }
                conflictWithoutWinner = false;
                conflictingKey = null;
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
            if (discoveries.containsKey(idempotencyKey)) {
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
            cases.put(analysisCase.caseId(), analysisCase);
            discoveries.put(idempotencyKey, analysisCase);
            discoveredSaves++;
        }

        @Override
        public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(discoveries.get(idempotencyKey));
        }

        @Override
        public AnalysisCase getById(UUID caseId) {
            var analysisCase = cases.get(caseId);
            if (analysisCase == null) {
                throw new CaseNotFound(caseId);
            }
            return analysisCase;
        }

        private void failNextDiscoveryWithWinner(String idempotencyKey, AnalysisCase winner) {
            conflictingKey = idempotencyKey;
            winnerAfterConflict = winner;
        }

        private void failNextDiscoveryWithoutWinner() {
            conflictWithoutWinner = true;
        }

        private int saves() {
            return saves;
        }

        private int discoveredSaves() {
            return discoveredSaves;
        }
    }
}
