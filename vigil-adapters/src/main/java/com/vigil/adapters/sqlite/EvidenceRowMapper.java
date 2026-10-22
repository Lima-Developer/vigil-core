package com.vigil.adapters.sqlite;

import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.ProtectedEvidenceReference;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Maps {@code evidence} rows to {@link Evidence}. Stores only the safe payload and the opaque reference id. */
final class EvidenceRowMapper {
    static final String SELECT = """
            SELECT evidence_id, case_id, sequence, type, source, captured_at, collector_version, confidence,
                   payload, protected_reference
            FROM evidence WHERE case_id = ? ORDER BY sequence""";
    static final String INSERT = """
            INSERT INTO evidence (evidence_id, case_id, sequence, type, source, captured_at, collector_version,
                                  confidence, payload, protected_reference)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    Evidence read(ResultSet row) throws SQLException {
        var type = PersistedValues.code(row, EvidenceType.class, "type");
        try {
            return new Evidence(
                    PersistedValues.uuid(row, "evidence_id"),
                    PersistedValues.uuid(row, "case_id"),
                    type,
                    PersistedValues.code(row, EvidenceSource.class, "source"),
                    PersistedValues.instant(row, "captured_at"),
                    PersistedValues.text(row, "collector_version"),
                    PersistedValues.code(row, EvidenceConfidence.class, "confidence"),
                    EvidencePayloadCodec.decode(type, PersistedValues.text(row, "payload")),
                    Optional.ofNullable(PersistedValues.optionalText(row, "protected_reference"))
                            .map(ProtectedEvidenceReference::new));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new CorruptAnalysisCaseDataException("Evidence row violates the domain rules.", invalid);
        }
    }

    long sequence(ResultSet row) throws SQLException {
        return PersistedValues.number(row, "sequence");
    }

    void bind(PreparedStatement statement, Evidence evidence, long sequence) throws SQLException {
        statement.setString(1, evidence.id().toString());
        statement.setString(2, evidence.caseId().toString());
        statement.setLong(3, sequence);
        statement.setString(4, evidence.type().name());
        statement.setString(5, evidence.source().name());
        statement.setString(6, SqliteInstantCodec.encode(evidence.capturedAt()));
        statement.setString(7, evidence.collectorVersion());
        statement.setString(8, evidence.confidence().name());
        statement.setString(9, EvidencePayloadCodec.encode(evidence.safePayload()));
        statement.setString(10, evidence.protectedReference().map(ProtectedEvidenceReference::opaqueIdentifier).orElse(null));
    }
}
