package com.vigil.adapters.sqlite;

import com.vigil.core.domain.AnalysisCaseDiscovered;
import com.vigil.core.domain.EvidenceCollected;
import com.vigil.core.domain.QueryTriaged;
import com.vigil.core.domain.interfaces.DomainEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/** Maps {@code domain_event} rows to the concrete {@link DomainEvent} types through explicit type codes. */
final class DomainEventRowMapper {
    static final String ANALYSIS_CASE_DISCOVERED = "ANALYSIS_CASE_DISCOVERED";
    static final String QUERY_TRIAGED = "QUERY_TRIAGED";
    static final String EVIDENCE_COLLECTED = "EVIDENCE_COLLECTED";

    static final String SELECT_BY_ID = """
            SELECT event_id, case_id, event_type, actor, occurred_at, event_version, evidence_id
            FROM domain_event WHERE event_id = ?""";
    static final String INSERT = """
            INSERT INTO domain_event (event_id, case_id, sequence, event_type, actor, occurred_at, event_version,
                                      evidence_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    static final String NEXT_SEQUENCE = "SELECT COALESCE(MAX(sequence), 0) + 1 FROM domain_event WHERE case_id = ?";

    DomainEvent read(ResultSet row) throws SQLException {
        var eventId = PersistedValues.uuid(row, "event_id");
        var caseId = PersistedValues.uuid(row, "case_id");
        var type = PersistedValues.text(row, "event_type");
        var actor = PersistedValues.text(row, "actor");
        var occurredAt = PersistedValues.instant(row, "occurred_at");
        var version = PersistedValues.text(row, "event_version");
        var evidenceId = PersistedValues.optionalText(row, "evidence_id");
        try {
            return switch (type) {
                case ANALYSIS_CASE_DISCOVERED -> new AnalysisCaseDiscovered(eventId, caseId, actor, occurredAt, version);
                case QUERY_TRIAGED -> new QueryTriaged(eventId, caseId, actor, occurredAt, version);
                case EVIDENCE_COLLECTED -> new EvidenceCollected(eventId, caseId,
                        PersistedValues.uuid(evidenceId == null ? "" : evidenceId, "evidence_id"),
                        actor, occurredAt, version);
                default -> throw new CorruptAnalysisCaseDataException("Column event_type holds an unknown code.");
            };
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new CorruptAnalysisCaseDataException("Domain event row violates the domain rules.", invalid);
        }
    }

    void bind(PreparedStatement statement, DomainEvent event, long sequence) throws SQLException {
        statement.setString(1, event.eventId().toString());
        statement.setString(2, event.caseId().toString());
        statement.setLong(3, sequence);
        statement.setString(4, typeCode(event));
        statement.setString(5, event.actor());
        statement.setString(6, SqliteInstantCodec.encode(event.occurredAt()));
        statement.setString(7, event.version());
        if (event instanceof EvidenceCollected collected) {
            statement.setString(8, collected.evidenceId().toString());
        } else {
            statement.setNull(8, Types.VARCHAR);
        }
    }

    static String typeCode(DomainEvent event) {
        return switch (event) {
            case AnalysisCaseDiscovered ignored -> ANALYSIS_CASE_DISCOVERED;
            case QueryTriaged ignored -> QUERY_TRIAGED;
            case EvidenceCollected ignored -> EVIDENCE_COLLECTED;
            default -> throw new IllegalArgumentException("Unsupported domain event type.");
        };
    }
}
