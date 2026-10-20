package com.vigil.adapters.sqlite;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.CaseEvent;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CaseStatus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Maps {@code analysis_case} rows and rebuilds the aggregate through its validated reconstitution factory. */
final class AnalysisCaseRowMapper {
    private static final String COLUMNS = """
            case_id, created_at, status, version, query_fingerprint, fingerprint_algorithm_version,
            discovery_idempotency_key""";
    static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM analysis_case WHERE case_id = ?";
    static final String SELECT_BY_KEY = "SELECT " + COLUMNS + " FROM analysis_case WHERE discovery_idempotency_key = ?";
    static final String INSERT = """
            INSERT INTO analysis_case (case_id, created_at, status, version, query_fingerprint,
                                       fingerprint_algorithm_version, discovery_idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";
    static final String UPDATE = """
            UPDATE analysis_case SET status = ?, version = version + 1 WHERE case_id = ? AND version = ?""";

    /** Flat view of one {@code analysis_case} row; never leaves the adapter. */
    record Row(UUID caseId, Instant createdAt, CaseStatus status, long version,
               Optional<QueryFingerprint> fingerprint, Optional<String> discoveryKey) {
    }

    Row read(ResultSet row) throws SQLException {
        var fingerprint = PersistedValues.optionalText(row, "query_fingerprint");
        var algorithm = PersistedValues.optionalText(row, "fingerprint_algorithm_version");
        if ((fingerprint == null) != (algorithm == null)) {
            throw new CorruptAnalysisCaseDataException("Fingerprint and algorithm version must be set together.");
        }
        try {
            return new Row(
                    PersistedValues.uuid(row, "case_id"),
                    PersistedValues.instant(row, "created_at"),
                    PersistedValues.code(row, CaseStatus.class, "status"),
                    PersistedValues.number(row, "version"),
                    fingerprint == null ? Optional.empty() : Optional.of(new QueryFingerprint(fingerprint, algorithm)),
                    Optional.ofNullable(PersistedValues.optionalText(row, "discovery_idempotency_key")));
        } catch (IllegalArgumentException invalid) {
            throw new CorruptAnalysisCaseDataException("Analysis case row violates the domain rules.", invalid);
        }
    }

    AnalysisCase toAggregate(Row row, List<CaseEvent> events, List<Evidence> evidences) {
        try {
            return AnalysisCase.reconstitute(row.caseId(), row.createdAt(), row.fingerprint(), row.status(),
                    row.version(), events, evidences);
        } catch (IllegalArgumentException | NullPointerException inconsistent) {
            throw new CorruptAnalysisCaseDataException("Persisted analysis case history is inconsistent.", inconsistent);
        }
    }

    void bindInsert(PreparedStatement statement, AnalysisCase analysisCase, long version, String discoveryKey)
            throws SQLException {
        statement.setString(1, analysisCase.caseId().toString());
        statement.setString(2, SqliteInstantCodec.encode(analysisCase.createdAt()));
        statement.setString(3, analysisCase.status().name());
        statement.setLong(4, version);
        var fingerprint = analysisCase.queryFingerprint();
        if (fingerprint.isPresent()) {
            statement.setString(5, fingerprint.get().value());
            statement.setString(6, fingerprint.get().algorithmVersion());
        } else {
            statement.setNull(5, Types.VARCHAR);
            statement.setNull(6, Types.VARCHAR);
        }
        if (discoveryKey == null) {
            statement.setNull(7, Types.VARCHAR);
        } else {
            statement.setString(7, discoveryKey);
        }
    }

    void bindUpdate(PreparedStatement statement, AnalysisCase analysisCase) throws SQLException {
        statement.setString(1, analysisCase.status().name());
        statement.setString(2, analysisCase.caseId().toString());
        statement.setLong(3, analysisCase.version());
    }
}
