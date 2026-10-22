package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import com.vigil.application.casework.DiscoverObservedQueryCommand;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseCommand;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.exception.InvalidAnalysisCaseTransition;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.ObservedQuery;
import com.vigil.core.domain.ObservedQueryMetrics;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.enums.CommandType;
import com.vigil.core.domain.enums.TelemetrySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAnalysisCaseUseCasesIT {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID FIRST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path directory;

    private SqliteAnalysisCaseRepository repository;

    @BeforeEach
    void migrate() throws SQLException {
        var factory = new SqliteConnectionFactory(directory.resolve("vigil.db"));
        try (var connection = factory.open()) {
            new SqlMigrationRunner(() -> NOW).migrate(connection);
        }
        repository = new SqliteAnalysisCaseRepository(factory);
    }

    @Test
    void shouldDiscoverOncePerKeyAndTriageAgainstTheRealAdapter() {
        var discover = new DiscoverObservedQueryUseCase(repository, () -> NOW, ids(FIRST_ID, SECOND_ID));
        var triage = new TriageAnalysisCaseUseCase(repository, () -> NOW.plusSeconds(5));

        var first = discover.execute(command("observation-1"));
        var retry = discover.execute(command("observation-1"));
        triage.execute(new TriageAnalysisCaseCommand(first.caseId(), "Slow query.", "vigil-core"));

        var stored = repository.getById(FIRST_ID);
        assertAll(
                () -> assertTrue(first.created()),
                () -> assertFalse(retry.created()),
                () -> assertEquals(FIRST_ID, retry.caseId()),
                () -> assertEquals(CaseStatus.TRIAGED, stored.status()),
                () -> assertEquals(2, stored.version()),
                () -> assertEquals(Optional.of(new QueryFingerprint("sha256:observed-query", "v1")), stored.queryFingerprint()),
                () -> assertThrows(InvalidAnalysisCaseTransition.class, () -> triage.execute(
                        new TriageAnalysisCaseCommand(FIRST_ID, "Again.", "vigil-core")))
        );
    }

    @Test
    void shouldReturnTheWinnerWhenDiscoveryConflictsAfterTheLookup() {
        var racing = new RacingRepository(repository);
        var discover = new DiscoverObservedQueryUseCase(racing, () -> NOW, ids(SECOND_ID));

        var result = discover.execute(command("observation-1"));

        assertAll(
                () -> assertFalse(result.created()),
                () -> assertEquals(FIRST_ID, result.caseId()),
                () -> assertEquals(1, racing.conflicts.get())
        );
    }

    @Test
    void shouldPropagateCaseNotFoundFromTriage() {
        var triage = new TriageAnalysisCaseUseCase(repository, () -> NOW);

        assertThrows(CaseNotFound.class,
                () -> triage.execute(new TriageAnalysisCaseCommand(FIRST_ID, "Reason.", "vigil-core")));
    }

    /** Lets a competing discovery win between the use case's lookup and its save. */
    private static final class RacingRepository implements AnalysisCaseRepository {
        private final AnalysisCaseRepository delegate;
        private final AtomicInteger conflicts = new AtomicInteger();
        private boolean raced;

        private RacingRepository(AnalysisCaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate {
            delegate.save(analysisCase);
        }

        @Override
        public void saveDiscovered(AnalysisCase analysisCase, String key) throws ConcurrentCaseUpdate {
            if (!raced) {
                raced = true;
                delegate.saveDiscovered(AnalysisCase.discover(FIRST_ID, NOW), key);
            }
            try {
                delegate.saveDiscovered(analysisCase, key);
            } catch (ConcurrentCaseUpdate conflict) {
                conflicts.incrementAndGet();
                throw conflict;
            }
        }

        @Override
        public Optional<AnalysisCase> findByIdempotencyKey(String key) {
            return raced ? delegate.findByIdempotencyKey(key) : Optional.empty();
        }

        @Override
        public AnalysisCase getById(UUID caseId) throws CaseNotFound {
            return delegate.getById(caseId);
        }
    }

    private static com.vigil.application.port.out.interfaces.IdGenerator ids(UUID... ids) {
        var next = new AtomicInteger();
        return () -> ids[next.getAndIncrement()];
    }

    private static DiscoverObservedQueryCommand command(String key) {
        return new DiscoverObservedQueryCommand(new ObservedQuery(
                new QueryFingerprint("sha256:observed-query", "v1"),
                TelemetrySource.PG_STAT_STATEMENTS,
                NOW,
                Optional.empty(),
                CommandType.SELECT,
                new ObservationWindow(NOW.minusSeconds(60), NOW),
                new ObservedQueryMetrics(10, Duration.ofSeconds(2), 100)), key);
    }
}
