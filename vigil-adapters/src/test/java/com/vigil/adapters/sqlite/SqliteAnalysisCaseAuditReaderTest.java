package com.vigil.adapters.sqlite;

import com.vigil.application.port.out.interfaces.AuditedEvent;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.DomainEventMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteAnalysisCaseAuditReaderTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CASE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path directory;

    private SqliteConnectionFactory factory;
    private SqliteAnalysisCaseRepository repository;
    private SqliteAnalysisCaseAuditReader reader;

    @BeforeEach
    void migrate() {
        factory = SqliteDatabaseInitializer.initialize(directory.resolve("vigil.db"), () -> NOW);
        repository = new SqliteAnalysisCaseRepository(factory);
        reader = new SqliteAnalysisCaseAuditReader(factory);
    }

    @Test
    void shouldListTypesAndMetadataInRecordingOrderForTheRequestedCaseOnly() {
        save(CASE_ID, "33333333-3333-3333-3333-333333333333", "44444444-4444-4444-4444-444444444444");
        save(OTHER_CASE_ID, "55555555-5555-5555-5555-555555555555", "66666666-6666-6666-6666-666666666666");

        assertEquals(List.of(
                new AuditedEvent("ANALYSIS_CASE_DISCOVERED", "vigil-system", NOW, "1"),
                new AuditedEvent("QUERY_TRIAGED", "operator", NOW.plusSeconds(1), "1")), reader.eventsOf(CASE_ID));
    }

    @Test
    void shouldReturnNoEventsForAnUnknownCase() {
        assertEquals(List.of(), reader.eventsOf(CASE_ID));
    }

    @Test
    void shouldRejectANullCaseId() {
        assertThrows(NullPointerException.class, () -> reader.eventsOf(null));
    }

    @Test
    void shouldWrapStorageFailuresWithoutLeakingThem() {
        var broken = new SqliteAnalysisCaseAuditReader(new SqliteConnectionFactory(directory.resolve("missing/x.db")));

        var failure = assertThrows(SqlitePersistenceException.class, () -> broken.eventsOf(CASE_ID));

        assertAll(
                () -> assertEquals("SQLite operation on the case audit failed.", failure.getMessage()),
                () -> assertEquals(java.sql.SQLException.class, failure.getCause().getClass())
        );
    }

    private void save(UUID caseId, String discoveredEventId, String triagedEventId) {
        var analysisCase = AnalysisCase.discover(caseId, NOW,
                new DomainEventMetadata(UUID.fromString(discoveredEventId), "vigil-system", NOW, "1"));
        analysisCase.triage("Reason.", new DomainEventMetadata(
                UUID.fromString(triagedEventId), "operator", NOW.plusSeconds(1), "1"));
        repository.save(analysisCase);
    }
}
