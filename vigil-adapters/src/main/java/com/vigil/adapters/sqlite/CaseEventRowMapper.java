package com.vigil.adapters.sqlite;

import com.vigil.core.domain.CaseEvent;
import com.vigil.core.domain.enums.CaseStatus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Maps {@code case_event} rows to {@link CaseEvent}; the persisted {@code sequence} defines the order. */
final class CaseEventRowMapper {
    static final String SELECT = """
            SELECT sequence, from_status, to_status, actor, reason, occurred_at
            FROM case_event WHERE case_id = ? ORDER BY sequence""";
    static final String INSERT = """
            INSERT INTO case_event (case_id, sequence, from_status, to_status, actor, reason, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    CaseEvent read(ResultSet row) throws SQLException {
        return new CaseEvent(
                PersistedValues.code(row, CaseStatus.class, "from_status"),
                PersistedValues.code(row, CaseStatus.class, "to_status"),
                PersistedValues.text(row, "actor"),
                PersistedValues.text(row, "reason"),
                PersistedValues.instant(row, "occurred_at"));
    }

    long sequence(ResultSet row) throws SQLException {
        return PersistedValues.number(row, "sequence");
    }

    void bind(PreparedStatement statement, UUID caseId, long sequence, CaseEvent event) throws SQLException {
        statement.setString(1, caseId.toString());
        statement.setLong(2, sequence);
        statement.setString(3, event.fromStatus().name());
        statement.setString(4, event.toStatus().name());
        statement.setString(5, event.actor());
        statement.setString(6, event.reason());
        statement.setString(7, SqliteInstantCodec.encode(event.occuredAt()));
    }
}
