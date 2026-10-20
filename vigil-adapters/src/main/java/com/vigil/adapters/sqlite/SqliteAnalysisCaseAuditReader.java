package com.vigil.adapters.sqlite;

import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AuditedEvent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQLite adapter for {@link AnalysisCaseAuditReader}. Reads only type and metadata columns of {@code domain_event},
 * ordered by their recording sequence, on a read connection that is closed before returning.
 */
public final class SqliteAnalysisCaseAuditReader implements AnalysisCaseAuditReader {
    private static final String SELECT = """
            SELECT event_type, actor, occurred_at, event_version
            FROM domain_event WHERE case_id = ? ORDER BY sequence""";

    private final SqliteConnectionFactory connections;

    public SqliteAnalysisCaseAuditReader(SqliteConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "Connection factory must not be null.");
    }

    @Override
    public List<AuditedEvent> eventsOf(UUID caseId) {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        try (var connection = connections.open();
             var select = connection.prepareStatement(SELECT)) {
            select.setString(1, caseId.toString());
            try (var result = select.executeQuery()) {
                var events = new ArrayList<AuditedEvent>();
                while (result.next()) {
                    events.add(read(result));
                }
                return List.copyOf(events);
            }
        } catch (SQLException failure) {
            throw new SqlitePersistenceException("SQLite operation on the case audit failed.", failure);
        }
    }

    private static AuditedEvent read(java.sql.ResultSet row) throws SQLException {
        try {
            return new AuditedEvent(PersistedValues.text(row, "event_type"), PersistedValues.text(row, "actor"),
                    PersistedValues.instant(row, "occurred_at"), PersistedValues.text(row, "event_version"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new CorruptAnalysisCaseDataException("Domain event row violates the domain rules.", invalid);
        }
    }
}
