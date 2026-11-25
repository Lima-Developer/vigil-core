package com.vigil.adapters.sqlite;

import com.vigil.core.domain.CaseEvent;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.enums.CaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vigil.adapters.sqlite.RowMapperTestSupport.read;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisCaseRowMapperTest {
    private static final String CASE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String AT = "2026-09-07T12:00:00.123456789Z";
    private static final String FINGERPRINT =
            "hmac-sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final AnalysisCaseRowMapper mapper = new AnalysisCaseRowMapper();

    private static String row(String caseId, String createdAt, String status, String version, String fingerprint,
                              String algorithm, String key) {
        return "%s AS case_id, %s AS created_at, %s AS status, %s AS version, %s AS query_fingerprint, "
                .formatted(caseId, createdAt, status, version, fingerprint)
                + "%s AS fingerprint_algorithm_version, %s AS discovery_idempotency_key".formatted(algorithm, key);
    }

    private static String valid(String status) {
        return row("'" + CASE_ID + "'", "'" + AT + "'", "'" + status + "'", "7", "'" + FINGERPRINT + "'", "'v1'", "'key'");
    }

    @Test
    void shouldReadEveryColumnOfAValidRow() throws SQLException {
        var row = read(valid("TRIAGED"), mapper::read);

        assertAll(
                () -> assertEquals(UUID.fromString(CASE_ID), row.caseId()),
                () -> assertEquals(Instant.parse(AT), row.createdAt()),
                () -> assertEquals(CaseStatus.TRIAGED, row.status()),
                () -> assertEquals(7, row.version()),
                () -> assertEquals(Optional.of(new QueryFingerprint(FINGERPRINT, "v1")), row.fingerprint()),
                () -> assertEquals(Optional.of("key"), row.discoveryKey())
        );
    }

    @Test
    void shouldReadOptionalColumnsAsEmpty() throws SQLException {
        var row = read(row("'" + CASE_ID + "'", "'" + AT + "'", "'DISCOVERED'", "0", "NULL", "NULL", "NULL"), mapper::read);

        assertAll(
                () -> assertEquals(Optional.empty(), row.fingerprint()),
                () -> assertEquals(Optional.empty(), row.discoveryKey())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_STATUS", "", "triaged"})
    void shouldRejectUnknownStatusInsteadOfDefaulting(String status) {
        assertThrows(CorruptAnalysisCaseDataException.class, () -> read(valid(status), mapper::read));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'nope', '" + AT + "', 'DISCOVERED', 1, NULL, NULL, NULL",
            "'11111111-1111-1111-1111-11111111111G', '" + AT + "', 'DISCOVERED', 1, NULL, NULL, NULL",
            "'11111111111111111111111111111111', '" + AT + "', 'DISCOVERED', 1, NULL, NULL, NULL",
            "'" + CASE_ID + "', '2026-09-07 12:00:00', 'DISCOVERED', 1, NULL, NULL, NULL",
            "'" + CASE_ID + "', '" + AT + "', 'DISCOVERED', NULL, NULL, NULL, NULL",
            "'" + CASE_ID + "', NULL, 'DISCOVERED', 1, NULL, NULL, NULL",
            "'" + CASE_ID + "', '" + AT + "', 'DISCOVERED', 1, 'fp', NULL, NULL",
            "'" + CASE_ID + "', '" + AT + "', 'DISCOVERED', 1, NULL, 'v1', NULL",
            "'" + CASE_ID + "', '" + AT + "', 'DISCOVERED', 1, ' ', 'v1', NULL"
    })
    void shouldRejectInvalidValuesAsCorruptData(String values) {
        var columns = List.of("case_id", "created_at", "status", "version", "query_fingerprint",
                "fingerprint_algorithm_version", "discovery_idempotency_key");
        var literals = values.split(", ");
        var select = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            select.append(index == 0 ? "" : ", ").append(literals[index]).append(" AS ").append(columns.get(index));
        }

        assertThrows(CorruptAnalysisCaseDataException.class, () -> read(select.toString(), mapper::read));
    }

    @Test
    void shouldRejectInconsistentHistoryWhenReconstituting() throws SQLException {
        var row = read(valid("TRIAGED"), mapper::read);

        assertThrows(CorruptAnalysisCaseDataException.class, () -> mapper.toAggregate(row, List.<CaseEvent>of(), List.of()));
    }

    @Test
    void shouldRebuildTheAggregateFromARowWithItsVersion() throws SQLException {
        var row = read(valid("DISCOVERED"), mapper::read);

        var aggregate = mapper.toAggregate(row, List.of(), List.of());

        assertAll(
                () -> assertEquals(7, aggregate.version()),
                () -> assertEquals(row.fingerprint(), aggregate.queryFingerprint()),
                () -> assertEquals(row.createdAt(), aggregate.createdAt())
        );
    }
}
