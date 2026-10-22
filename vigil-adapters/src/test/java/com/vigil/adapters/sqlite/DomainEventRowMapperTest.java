package com.vigil.adapters.sqlite;

import com.vigil.core.domain.AnalysisCaseDiscovered;
import com.vigil.core.domain.EvidenceCollected;
import com.vigil.core.domain.QueryTriaged;
import com.vigil.core.domain.interfaces.DomainEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static com.vigil.adapters.sqlite.RowMapperTestSupport.read;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainEventRowMapperTest {
    private static final UUID EVENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVIDENCE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant AT = Instant.parse("2026-09-07T12:00:00.123456789Z");
    private final DomainEventRowMapper mapper = new DomainEventRowMapper();

    private static String row(String type, String evidenceId) {
        return "'%s' AS event_id, '%s' AS case_id, '%s' AS event_type, 'actor' AS actor, "
                .formatted(EVENT, CASE, type)
                + "'2026-09-07T12:00:00.123456789Z' AS occurred_at, '1' AS event_version, %s AS evidence_id"
                .formatted(evidenceId);
    }

    @Test
    void shouldMapEachTypeCodeToItsConcreteEvent() throws SQLException {
        assertAll(
                () -> assertEquals(new AnalysisCaseDiscovered(EVENT, CASE, "actor", AT, "1"),
                        read(row("ANALYSIS_CASE_DISCOVERED", "NULL"), mapper::read)),
                () -> assertEquals(new QueryTriaged(EVENT, CASE, "actor", AT, "1"),
                        read(row("QUERY_TRIAGED", "NULL"), mapper::read)),
                () -> assertEquals(new EvidenceCollected(EVENT, CASE, EVIDENCE, "actor", AT, "1"),
                        read(row("EVIDENCE_COLLECTED", "'" + EVIDENCE + "'"), mapper::read))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_EVENT", "", "query_triaged"})
    void shouldRejectUnknownTypeCodes(String type) {
        assertThrows(CorruptAnalysisCaseDataException.class, () -> read(row(type, "NULL"), mapper::read));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "'nope'"})
    void shouldRejectEvidenceCollectedWithoutAValidEvidenceId(String evidenceId) {
        assertThrows(CorruptAnalysisCaseDataException.class,
                () -> read(row("EVIDENCE_COLLECTED", evidenceId), mapper::read));
    }

    @Test
    void shouldNameTheTypeCodeOfEachEventAndRejectForeignImplementations() {
        DomainEvent foreign = new DomainEvent() {
            public UUID eventId() { return EVENT; }
            public UUID caseId() { return CASE; }
            public String actor() { return "a"; }
            public Instant occurredAt() { return AT; }
            public String version() { return "1"; }
        };
        assertAll(
                () -> assertEquals("QUERY_TRIAGED", DomainEventRowMapper.typeCode(new QueryTriaged(EVENT, CASE, "a", AT, "1"))),
                () -> assertEquals("ANALYSIS_CASE_DISCOVERED",
                        DomainEventRowMapper.typeCode(new AnalysisCaseDiscovered(EVENT, CASE, "a", AT, "1"))),
                () -> assertEquals("EVIDENCE_COLLECTED",
                        DomainEventRowMapper.typeCode(new EvidenceCollected(EVENT, CASE, EVIDENCE, "a", AT, "1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> DomainEventRowMapper.typeCode(foreign))
        );
    }
}
