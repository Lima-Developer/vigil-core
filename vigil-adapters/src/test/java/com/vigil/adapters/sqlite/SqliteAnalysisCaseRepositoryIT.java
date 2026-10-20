package com.vigil.adapters.sqlite;

import com.vigil.adapters.sqlite.migration.SqlMigrationRunner;
import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.DomainEventMetadata;
import com.vigil.core.domain.ProtectedEvidenceReference;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.interfaces.EvidencePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static com.vigil.adapters.sqlite.AnalysisCaseFixtures.CASE_ID;
import static com.vigil.adapters.sqlite.AnalysisCaseFixtures.CREATED_AT;
import static com.vigil.adapters.sqlite.AnalysisCaseFixtures.LATER;
import static com.vigil.adapters.sqlite.SqliteTestSupport.execute;
import static com.vigil.adapters.sqlite.SqliteTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAnalysisCaseRepositoryIT {
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path directory;

    private SqliteConnectionFactory factory;
    private SqliteAnalysisCaseRepository repository;

    @BeforeEach
    void migrateAndCreateRepository() throws SQLException {
        factory = new SqliteConnectionFactory(directory.resolve("vigil.db"));
        try (var connection = factory.open()) {
            new SqlMigrationRunner(() -> Instant.parse("2026-09-07T10:00:00Z")).migrate(connection);
        }
        repository = new SqliteAnalysisCaseRepository(factory);
    }

    private SqliteAnalysisCaseRepository reopened() {
        return new SqliteAnalysisCaseRepository(new SqliteConnectionFactory(directory.resolve("vigil.db")));
    }

    private List<String> query(String sql) throws SQLException {
        try (var connection = factory.open()) {
            return rows(connection, sql);
        }
    }

    private void run(String sql) throws SQLException {
        try (var connection = factory.open()) {
            execute(connection, sql);
        }
    }

    static Stream<Arguments> collectedCases() {
        var reference = Optional.of(new ProtectedEvidenceReference("vault://evidence/42"));
        return Stream.of(
                Arguments.of("metric with reference", AnalysisCaseFixtures.metric(), reference),
                Arguments.of("plan summary without reference", AnalysisCaseFixtures.plan(), Optional.empty()),
                Arguments.of("relation metadata with reference", AnalysisCaseFixtures.relation(), reference)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collectedCases")
    void shouldRebuildAnEvidenceCollectedAggregateAfterReopening(
            String name, EvidencePayload payload, Optional<ProtectedEvidenceReference> reference) {
        var original = AnalysisCaseFixtures.collected(payload, reference);
        repository.save(original);

        var loaded = reopened().getById(CASE_ID);

        assertAll(
                () -> assertEquals(1, original.version()),
                () -> assertEquals(1, loaded.version()),
                () -> assertEquals(CaseStatus.EVIDENCE_COLLECTED, loaded.status()),
                () -> assertEquals(original.createdAt(), loaded.createdAt()),
                () -> assertEquals(original.events(), loaded.events()),
                () -> assertEquals(original.evidences(), loaded.evidences()),
                () -> assertEquals(reference, loaded.evidences().getFirst().protectedReference()),
                () -> assertTrue(loaded.pendingEvents().isEmpty())
        );
    }

    @Test
    void shouldPreserveBigDecimalScaleAndNanosecondPrecision() {
        repository.save(AnalysisCaseFixtures.collected(AnalysisCaseFixtures.metric(), Optional.empty()));

        var loaded = reopened().getById(CASE_ID);
        var payload = (com.vigil.core.domain.MetricEvidencePayload) loaded.evidences().getFirst().safePayload();

        assertAll(
                () -> assertEquals("60000.500", payload.value().toString()),
                () -> assertEquals(CREATED_AT, loaded.createdAt()),
                () -> assertEquals(LATER, loaded.events().getFirst().occuredAt())
        );
    }

    @Test
    void shouldRebuildDiscoveredAndTriagedCasesWithAndWithoutFingerprint() {
        var withFingerprint = AnalysisCaseFixtures.discoveredWithFingerprint(OTHER_ID);
        repository.save(withFingerprint);
        repository.save(AnalysisCaseFixtures.triaged());

        var discovered = reopened().getById(OTHER_ID);
        var triaged = reopened().getById(CASE_ID);

        assertAll(
                () -> assertEquals(withFingerprint.queryFingerprint(), discovered.queryFingerprint()),
                () -> assertEquals(CaseStatus.DISCOVERED, discovered.status()),
                () -> assertTrue(discovered.events().isEmpty()),
                () -> assertEquals(Optional.empty(), triaged.queryFingerprint()),
                () -> assertEquals(CaseStatus.TRIAGED, triaged.status()),
                () -> assertEquals(AnalysisCaseFixtures.triaged().events(), triaged.events())
        );
    }

    @Test
    void shouldKeepAReloadedAggregateFunctional() {
        repository.save(AnalysisCaseFixtures.triaged());
        var loaded = reopened().getById(CASE_ID);

        loaded.collectEvidence(AnalysisCaseFixtures.evidence(AnalysisCaseFixtures.plan(), Optional.empty()),
                "collector", LATER.plusSeconds(5));
        var repository = reopened();
        repository.save(loaded);

        var again = repository.getById(CASE_ID);
        assertAll(
                () -> assertEquals(2, loaded.version()),
                () -> assertEquals(CaseStatus.EVIDENCE_COLLECTED, again.status()),
                () -> assertEquals(2, again.events().size()),
                () -> assertEquals(1, again.evidences().size())
        );
    }

    @Test
    void shouldKeepCaseEventSequenceWhenTransitionTimestampsAreEqual() {
        var analysisCase = AnalysisCaseFixtures.triaged();
        analysisCase.collectEvidence(AnalysisCaseFixtures.evidence(AnalysisCaseFixtures.plan(), Optional.empty()),
                "collector", LATER);
        repository.save(analysisCase);

        var loaded = reopened().getById(CASE_ID);

        assertEquals(analysisCase.events(), loaded.events());
        assertEquals(2, loaded.events().size());
        assertEquals(LATER, loaded.events().get(0).occuredAt());
        assertEquals(LATER, loaded.events().get(1).occuredAt());
    }

    @Test
    void shouldIncrementTheVersionOnlyWhenSomethingIsPersistedAndNeverDuplicateRows() throws SQLException {
        var analysisCase = AnalysisCaseFixtures.discovered();
        repository.save(analysisCase);
        repository.save(analysisCase);
        assertEquals(1, analysisCase.version());

        analysisCase.triage("Slow query.", "vigil-core", LATER);
        assertEquals(1, analysisCase.version());
        repository.save(analysisCase);
        repository.save(analysisCase);

        assertAll(
                () -> assertEquals(2, analysisCase.version()),
                () -> assertEquals(List.of("TRIAGED | 2"), query("SELECT status, version FROM analysis_case")),
                () -> assertEquals(List.of("1"), query("SELECT count(*) FROM case_event"))
        );
    }

    @Test
    void shouldRejectTheSecondOfTwoDivergentCopiesLoadedAtTheSameVersion() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        var first = repository.getById(CASE_ID);
        var second = repository.getById(CASE_ID);
        first.triage("Reason A.", "alice", LATER);
        second.triage("Reason B.", "bob", LATER);

        repository.save(first);
        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(second));

        assertAll(
                () -> assertEquals(1, second.version()),
                () -> assertEquals(List.of("alice | Reason A."), query("SELECT actor, reason FROM case_event")),
                () -> assertEquals(List.of("2"), query("SELECT version FROM analysis_case"))
        );
    }

    @Test
    void shouldAcceptAStaleCopyWhoseContentEqualsWhatWasPersisted() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        var first = repository.getById(CASE_ID);
        var second = repository.getById(CASE_ID);
        first.triage("Same reason.", "alice", LATER);
        second.triage("Same reason.", "alice", LATER);
        repository.save(first);

        repository.save(second);

        assertAll(
                () -> assertEquals(2, second.version()),
                () -> assertEquals(List.of("2"), query("SELECT version FROM analysis_case")),
                () -> assertEquals(List.of("1"), query("SELECT count(*) FROM case_event"))
        );
    }

    @Test
    void shouldRejectAStaleCopyWhenOnlyTheStatusMatches() {
        repository.save(AnalysisCaseFixtures.discovered());
        var first = repository.getById(CASE_ID);
        var second = repository.getById(CASE_ID);
        first.triage("Same reason.", "alice", LATER);
        second.triage("Same reason.", "alice", LATER.plusNanos(1));
        repository.save(first);

        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(second));
    }

    @Test
    void shouldRejectUnknownVersionsAndAggregatesAheadOfTheDatabase() throws SQLException {
        var ahead = AnalysisCase.reconstitute(CASE_ID, CREATED_AT, Optional.empty(), CaseStatus.DISCOVERED, 3,
                List.of(), List.of());
        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(ahead));
        assertEquals(List.of("0"), query("SELECT count(*) FROM analysis_case"));

        repository.save(AnalysisCaseFixtures.discovered());
        var future = AnalysisCase.reconstitute(CASE_ID, CREATED_AT, Optional.empty(), CaseStatus.DISCOVERED, 5,
                List.of(), List.of());
        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(future));
    }

    @Test
    void shouldLetExactlyOneOfTwoParallelWritersWin() throws Exception {
        repository.save(AnalysisCaseFixtures.discovered());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var copies = new ArrayList<AnalysisCase>();
            for (var reason : List.of("A", "B")) {
                var copy = repository.getById(CASE_ID);
                copy.triage("Reason " + reason, "actor-" + reason, LATER);
                copies.add(copy);
            }
            List<Future<Boolean>> results = copies.stream().map(copy -> executor.submit(() -> {
                start.await();
                try {
                    reopened().save(copy);
                    return true;
                } catch (ConcurrentCaseUpdate conflict) {
                    return false;
                }
            })).toList();
            start.countDown();

            var wins = 0;
            for (var result : results) {
                wins += result.get() ? 1 : 0;
            }
            assertEquals(1, wins);
            assertEquals(List.of("1"), query("SELECT count(*) FROM case_event"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPersistPendingDomainEventsAndConfirmThemOnlyAfterCommit() throws SQLException {
        var eventId = UUID.randomUUID();
        var analysisCase = AnalysisCase.discover(CASE_ID, CREATED_AT,
                new DomainEventMetadata(eventId, "vigil-core", CREATED_AT, "1"));

        repository.save(analysisCase);

        assertAll(
                () -> assertTrue(analysisCase.pendingEvents().isEmpty()),
                () -> assertEquals(List.of(eventId + " | ANALYSIS_CASE_DISCOVERED | 1 | null"),
                        query("SELECT event_id, event_type, event_version, evidence_id FROM domain_event")),
                () -> assertTrue(repository.getById(CASE_ID).pendingEvents().isEmpty())
        );
    }

    @Test
    void shouldPersistPendingDomainEventsWhenSavingByIdempotencyKey() throws SQLException {
        var eventId = UUID.randomUUID();
        var analysisCase = AnalysisCase.discover(CASE_ID, CREATED_AT,
                new DomainEventMetadata(eventId, "vigil-core", CREATED_AT, "1"));

        repository.saveDiscovered(analysisCase, "key-with-event");

        assertAll(
                () -> assertEquals(1, analysisCase.version()),
                () -> assertTrue(analysisCase.pendingEvents().isEmpty()),
                () -> assertEquals(List.of(eventId + " | ANALYSIS_CASE_DISCOVERED"),
                        query("SELECT event_id, event_type FROM domain_event")),
                () -> assertEquals(CASE_ID, repository.findByIdempotencyKey("key-with-event").orElseThrow().caseId())
        );
    }

    @Test
    void shouldPropagateNonUniqueDiscoveryInsertErrorsAsPersistenceFailures() throws SQLException {
        run("CREATE TRIGGER fail_discovery BEFORE INSERT ON analysis_case BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        var analysisCase = AnalysisCaseFixtures.discovered();

        var failure = assertThrows(SqlitePersistenceException.class,
                () -> repository.saveDiscovered(analysisCase, "key-1"));

        assertAll(
                () -> assertInstanceOf(SQLException.class, failure.getCause()),
                () -> assertEquals(0, analysisCase.version()),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM analysis_case"))
        );
    }

    @Test
    void shouldRejectAReplacementForThePersistedHistoryPrefixAtTheSameVersion() throws SQLException {
        repository.save(AnalysisCaseFixtures.triaged());
        var replacementHistory = List.of(new com.vigil.core.domain.CaseEvent(
                com.vigil.core.domain.enums.CaseStatus.DISCOVERED,
                CaseStatus.TRIAGED, "another-actor", "rewritten reason", LATER));
        var rewritten = AnalysisCase.reconstitute(CASE_ID, CREATED_AT, Optional.empty(), CaseStatus.TRIAGED, 1,
                replacementHistory, List.of());

        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(rewritten));

        assertAll(
                () -> assertEquals(List.of("vigil-core | Slow query."), query("SELECT actor, reason FROM case_event")),
                () -> assertEquals(List.of("1"), query("SELECT version FROM analysis_case"))
        );
    }

    @Test
    void shouldPersistTriageAndEvidenceDomainEventsWithTheirEvidenceLink() throws SQLException {
        var analysisCase = AnalysisCaseFixtures.discovered();
        analysisCase.triage("Slow.", new DomainEventMetadata(UUID.randomUUID(), "a", LATER, "1"));
        analysisCase.collectEvidence(AnalysisCaseFixtures.evidence(AnalysisCaseFixtures.relation(), Optional.empty()),
                new DomainEventMetadata(UUID.randomUUID(), "a", LATER.plusSeconds(1), "1"));

        repository.save(analysisCase);

        assertEquals(List.of("1 | QUERY_TRIAGED | null", "2 | EVIDENCE_COLLECTED | " + AnalysisCaseFixtures.EVIDENCE_ID),
                query("SELECT sequence, event_type, evidence_id FROM domain_event ORDER BY sequence"));
    }

    @Test
    void shouldRollBackEverythingWhenAnEventInsertFailsAndAllowRetryOfTheSameObject() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        var analysisCase = repository.getById(CASE_ID);
        var metadata = new DomainEventMetadata(UUID.randomUUID(), "a", LATER, "1");
        analysisCase.triage("Slow.", metadata);
        run("""
                CREATE TRIGGER fail_event BEFORE INSERT ON domain_event
                BEGIN SELECT RAISE(ABORT, 'injected failure'); END""");

        var failure = assertThrows(SqlitePersistenceException.class, () -> repository.save(analysisCase));

        assertAll(
                () -> assertInstanceOf(SQLException.class, failure.getCause()),
                () -> assertEquals(1, analysisCase.version()),
                () -> assertEquals(1, analysisCase.pendingEvents().size()),
                () -> assertEquals(List.of("DISCOVERED | 1"), query("SELECT status, version FROM analysis_case")),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM case_event")),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM domain_event"))
        );

        run("DROP TRIGGER fail_event");
        repository.save(analysisCase);

        assertAll(
                () -> assertEquals(2, analysisCase.version()),
                () -> assertTrue(analysisCase.pendingEvents().isEmpty()),
                () -> assertEquals(List.of("1"), query("SELECT count(*) FROM domain_event"))
        );
    }

    @Test
    void shouldRollBackDiscoveryKeyAndDomainEventTogetherAndAllowRetry() throws SQLException {
        var eventId = UUID.randomUUID();
        var analysisCase = AnalysisCase.discover(CASE_ID, CREATED_AT,
                new DomainEventMetadata(eventId, "vigil-core", CREATED_AT, "1"));
        run("CREATE TRIGGER fail_discovery_event BEFORE INSERT ON domain_event BEGIN SELECT RAISE(ABORT, 'injected failure'); END");

        assertThrows(SqlitePersistenceException.class, () -> repository.saveDiscovered(analysisCase, "rollback-key"));

        assertAll(
                () -> assertEquals(0, analysisCase.version()),
                () -> assertEquals(1, analysisCase.pendingEvents().size()),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM analysis_case")),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM domain_event")),
                () -> assertEquals(Optional.empty(), repository.findByIdempotencyKey("rollback-key"))
        );

        run("DROP TRIGGER fail_discovery_event");
        repository.saveDiscovered(analysisCase, "rollback-key");
        assertAll(
                () -> assertEquals(1, analysisCase.version()),
                () -> assertTrue(analysisCase.pendingEvents().isEmpty()),
                () -> assertEquals(CASE_ID, repository.findByIdempotencyKey("rollback-key").orElseThrow().caseId()),
                () -> assertEquals(List.of(eventId.toString()), query("SELECT event_id FROM domain_event"))
        );
    }

    @Test
    void shouldRollBackANewCaseWhenItsEvidenceInsertFails() throws SQLException {
        run("CREATE TRIGGER fail_evidence BEFORE INSERT ON evidence BEGIN SELECT RAISE(ABORT, 'boom'); END");
        var analysisCase = AnalysisCaseFixtures.collected(AnalysisCaseFixtures.metric(), Optional.empty());

        assertThrows(SqlitePersistenceException.class, () -> repository.save(analysisCase));

        assertAll(
                () -> assertEquals(0, analysisCase.version()),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM analysis_case")),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM case_event"))
        );
    }

    @Test
    void shouldTreatAnUpdateThatAffectsNoRowAsAConflict() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        var analysisCase = repository.getById(CASE_ID);
        analysisCase.triage("Slow.", "a", LATER);
        run("CREATE TRIGGER skip_update BEFORE UPDATE ON analysis_case BEGIN SELECT RAISE(IGNORE); END");

        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(analysisCase));

        assertAll(
                () -> assertEquals(List.of("DISCOVERED | 1"), query("SELECT status, version FROM analysis_case")),
                () -> assertEquals(List.of("0"), query("SELECT count(*) FROM case_event"))
        );
    }

    @Test
    void shouldRejectADomainEventIdAlreadyStoredWithDifferentContent() {
        var eventId = UUID.randomUUID();
        repository.save(AnalysisCase.discover(CASE_ID, CREATED_AT,
                new DomainEventMetadata(eventId, "a", CREATED_AT, "1")));
        var other = AnalysisCase.discover(OTHER_ID, CREATED_AT, new DomainEventMetadata(eventId, "a", CREATED_AT, "1"));

        assertThrows(ConcurrentCaseUpdate.class, () -> repository.save(other));
    }

    @Test
    void shouldSaveADiscoveredCaseOncePerKeyAndFindIt() throws SQLException {
        var analysisCase = AnalysisCaseFixtures.discoveredWithFingerprint(CASE_ID);
        repository.saveDiscovered(analysisCase, "key-1");
        repository.saveDiscovered(analysisCase, "key-1");

        var found = reopened().findByIdempotencyKey("key-1");

        assertAll(
                () -> assertEquals(1, analysisCase.version()),
                () -> assertEquals(CASE_ID, found.orElseThrow().caseId()),
                () -> assertEquals(analysisCase.queryFingerprint(), found.orElseThrow().queryFingerprint()),
                () -> assertEquals(List.of("1"), query("SELECT count(*) FROM analysis_case")),
                () -> assertEquals(Optional.empty(), repository.findByIdempotencyKey("missing"))
        );
    }

    @Test
    void shouldRaiseConflictWhenAnotherCaseOwnsTheKeyAndKeepTheWinner() throws SQLException {
        repository.saveDiscovered(AnalysisCaseFixtures.discovered(), "key-1");
        var loser = AnalysisCase.discover(OTHER_ID, CREATED_AT);

        assertThrows(ConcurrentCaseUpdate.class, () -> repository.saveDiscovered(loser, "key-1"));

        assertAll(
                () -> assertEquals(0, loser.version()),
                () -> assertEquals(List.of(CASE_ID.toString()), query("SELECT case_id FROM analysis_case")),
                () -> assertEquals(CASE_ID, repository.findByIdempotencyKey("key-1").orElseThrow().caseId())
        );
    }

    @Test
    void shouldRaiseConflictWhenTheCaseExistsWithoutThatKeyOrReplayDiverged() {
        repository.save(AnalysisCaseFixtures.discovered());
        assertThrows(ConcurrentCaseUpdate.class,
                () -> repository.saveDiscovered(AnalysisCaseFixtures.discovered(), "key-1"));

        repository.saveDiscovered(AnalysisCase.discover(OTHER_ID, CREATED_AT), "key-2");
        var diverged = repository.getById(OTHER_ID);
        diverged.triage("Slow.", "a", LATER);
        assertThrows(ConcurrentCaseUpdate.class, () -> repository.saveDiscovered(diverged, "key-2"));
    }

    @Test
    void shouldLetOnlyOneParallelDiscoveryWinTheKey() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = List.of(CASE_ID, OTHER_ID).stream().map(id -> executor.submit(() -> {
                start.await();
                try {
                    reopened().saveDiscovered(AnalysisCase.discover(id, CREATED_AT), "key-1");
                    return true;
                } catch (ConcurrentCaseUpdate conflict) {
                    return false;
                }
            })).toList();
            start.countDown();

            var wins = 0;
            for (var result : results) {
                wins += result.get() ? 1 : 0;
            }
            assertEquals(1, wins);
            assertEquals(List.of("1"), query("SELECT count(*) FROM analysis_case"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReportMissingCasesAsNotFoundAndRejectInvalidArguments() {
        assertAll(
                () -> assertThrows(CaseNotFound.class, () -> repository.getById(CASE_ID)),
                () -> assertThrows(NullPointerException.class, () -> repository.getById(null)),
                () -> assertThrows(NullPointerException.class, () -> repository.save(null)),
                () -> assertThrows(NullPointerException.class, () -> repository.findByIdempotencyKey(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> repository.findByIdempotencyKey(" ")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.saveDiscovered(AnalysisCaseFixtures.discovered(), "")),
                () -> assertThrows(NullPointerException.class, () -> new SqliteAnalysisCaseRepository(null))
        );
    }

    @Test
    void shouldReportOperationalFailuresAsPersistenceErrorsNotConflictsOrNotFound() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        run("DROP TABLE domain_event");
        run("DROP TABLE evidence");
        run("DROP TABLE case_event");

        assertAll(
                () -> assertThrows(SqlitePersistenceException.class, () -> repository.getById(CASE_ID)),
                () -> assertThrows(SqlitePersistenceException.class, () -> repository.save(AnalysisCaseFixtures.triaged()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "UPDATE analysis_case SET status = 'ARCHIVED'",
            "UPDATE analysis_case SET status = 'DISCOVERED'",
            "UPDATE analysis_case SET created_at = 'yesterday'",
            "UPDATE analysis_case SET query_fingerprint = 'only-one-side'",
            "UPDATE case_event SET sequence = 5 WHERE sequence = 2",
            "UPDATE case_event SET to_status = 'ARCHIVED' WHERE sequence = 1",
            "UPDATE case_event SET to_status = 'EVIDENCE_COLLECTED' WHERE sequence = 1",
            "UPDATE case_event SET occurred_at = '2026' WHERE sequence = 1",
            "UPDATE evidence SET payload = 'metric=CALLS'",
            "UPDATE evidence SET payload = 'value=1;metric=CALLS;unit=ROWS;windowStart=a;windowEnd=b'",
            "UPDATE evidence SET type = 'UNKNOWN'",
            "UPDATE evidence SET source = 'UNKNOWN'",
            "UPDATE evidence SET sequence = 2",
            "UPDATE evidence SET evidence_id = 'not-a-uuid'",
            "DELETE FROM evidence"
    })
    void shouldReportCorruptPersistedDataInsteadOfFallingBack(String corruption) throws SQLException {
        repository.save(AnalysisCaseFixtures.collected(AnalysisCaseFixtures.metric(), Optional.empty()));
        try (var connection = factory.open()) {
            execute(connection, "PRAGMA ignore_check_constraints = ON");
            execute(connection, "PRAGMA foreign_keys = OFF");
            execute(connection, corruption);
        }

        assertThrows(CorruptAnalysisCaseDataException.class, () -> reopened().getById(CASE_ID));
    }

    @Test
    void shouldNotLetCorruptCaseIdsEscapeAsMissingCases() throws SQLException {
        repository.save(AnalysisCaseFixtures.discovered());
        try (var connection = factory.open()) {
            execute(connection, "PRAGMA ignore_check_constraints = ON");
            execute(connection, "UPDATE analysis_case SET status = 'ARCHIVED', discovery_idempotency_key = 'k'");
        }

        assertAll(
                () -> assertThrows(CorruptAnalysisCaseDataException.class, () -> repository.findByIdempotencyKey("k")),
                () -> assertThrows(CorruptAnalysisCaseDataException.class, () -> repository.save(AnalysisCaseFixtures.discovered()))
        );
    }

    @Test
    void shouldNeverStoreProtectedReferenceTextRepresentationOrArtifactContent() throws SQLException {
        repository.save(AnalysisCaseFixtures.collected(AnalysisCaseFixtures.metric(),
                Optional.of(new ProtectedEvidenceReference("vault://evidence/42"))));

        assertAll(
                () -> assertEquals(List.of("vault://evidence/42"), query("SELECT protected_reference FROM evidence")),
                () -> assertFalse(query("SELECT payload FROM evidence").getFirst().contains("redacted"))
        );
    }
}
