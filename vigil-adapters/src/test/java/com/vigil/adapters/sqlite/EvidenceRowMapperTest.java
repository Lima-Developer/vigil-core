package com.vigil.adapters.sqlite;

import com.vigil.core.domain.ProtectedEvidenceReference;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.interfaces.EvidencePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Stream;

import static com.vigil.adapters.sqlite.RowMapperTestSupport.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceRowMapperTest {
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CASE = "11111111-1111-1111-1111-111111111111";
    private final EvidenceRowMapper mapper = new EvidenceRowMapper();

    private static String row(String type, String source, String payload, String reference) {
        return "'%s' AS evidence_id, '%s' AS case_id, '%s' AS type, '%s' AS source, "
                .formatted(ID, CASE, type, source)
                + "'2026-09-07T12:00:30.999999999Z' AS captured_at, 'collector-v1' AS collector_version, "
                + "'HIGH' AS confidence, %s AS payload, %s AS protected_reference".formatted(payload, reference);
    }

    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of(AnalysisCaseFixtures.metric(), "metric=TOTAL_EXECUTION_TIME;value=60000.500;"
                        + "unit=MILLISECONDS;windowStart=2026-09-07T11:00:00.000000001Z;"
                        + "windowEnd=2026-09-07T12:00:00.123456789Z"),
                Arguments.of(AnalysisCaseFixtures.plan(),
                        "nodes=SEQUENTIAL_SCAN,HASH_JOIN;estimatedRows=1000000"),
                Arguments.of(AnalysisCaseFixtures.relation(),
                        "relation=relation_orders;estimatedRows=42;indexCount=3;compatibleIndexExists=true")
        );
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void shouldEncodeEachPayloadInItsExplicitFormatAndDecodeItBack(EvidencePayload payload, String text) {
        assertEquals(text, EvidencePayloadCodec.encode(payload));
        assertEquals(payload, EvidencePayloadCodec.decode(payload.type(), text));
    }

    @Test
    void shouldPreserveFalseForRelationCompatibilityFlag() {
        var payload = new com.vigil.core.domain.RelationMetadataEvidencePayload(
                new com.vigil.core.domain.SafeRelationReference("relation_orders"), 42L, 3, false);
        var encoded = EvidencePayloadCodec.encode(payload);

        assertEquals("relation=relation_orders;estimatedRows=42;indexCount=3;compatibleIndexExists=false", encoded);
        assertEquals(payload, EvidencePayloadCodec.decode(EvidenceType.RELATION_METADATA, encoded));
    }

    @Test
    void shouldReadARowWithAndWithoutProtectedReference() throws SQLException {
        var payload = "'nodes=INDEX_SCAN;estimatedRows=5'";

        var plain = read(row("PLAN_SUMMARY", "POSTGRESQL_EXPLAIN", payload, "NULL"), mapper::read);
        var protectedRow = read(row("PLAN_SUMMARY", "POSTGRESQL_EXPLAIN", payload, "'vault://x'"), mapper::read);

        assertEquals(Optional.empty(), plain.protectedReference());
        assertEquals(Optional.of(new ProtectedEvidenceReference("vault://x")), protectedRow.protectedReference());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'nodes=INDEX_SCAN'",
            "'estimatedRows=5;nodes=INDEX_SCAN'",
            "'nodes=INDEX_SCAN;estimatedRows=five'",
            "'nodes=INDEX_SCAN;estimatedRows=-1'",
            "'nodes=BITMAP;estimatedRows=1'",
            "'nodes=;estimatedRows=1'",
            "'nodes=INDEX_SCAN;estimatedRows=1;extra=1'",
            "NULL"
    })
    void shouldRejectInvalidPayloadsAsCorruptData(String payload) {
        assertThrows(CorruptAnalysisCaseDataException.class,
                () -> read(row("PLAN_SUMMARY", "POSTGRESQL_EXPLAIN", payload, "NULL"), mapper::read));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "relation=Orders;estimatedRows=1;indexCount=1;compatibleIndexExists=true",
            "relation=relation_a;estimatedRows=1;indexCount=1;compatibleIndexExists=yes",
            "relation=relation_a;estimatedRows=1;indexCount=-1;compatibleIndexExists=true",
            "relation=relation_a;estimatedRows=1;indexCount=x;compatibleIndexExists=true"
    })
    void shouldRejectInvalidRelationPayloads(String payload) {
        assertThrows(CorruptAnalysisCaseDataException.class, () -> EvidencePayloadCodec.decode(EvidenceType.RELATION_METADATA, payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "metric=CALLS;value=1;unit=ROWS;windowStart=2026-09-07T11:00:00.000000000Z;windowEnd=2026-09-07T12:00:00.000000000Z",
            "metric=CALLS;value=-1;unit=EXECUTIONS;windowStart=2026-09-07T11:00:00.000000000Z;windowEnd=2026-09-07T12:00:00.000000000Z",
            "metric=CALLS;value=1;unit=EXECUTIONS;windowStart=2026-09-07T12:00:00.000000000Z;windowEnd=2026-09-07T11:00:00.000000000Z",
            "metric=CALLS;value=abc;unit=EXECUTIONS;windowStart=x;windowEnd=y",
            "metric=UNKNOWN;value=1;unit=EXECUTIONS;windowStart=x;windowEnd=y"
    })
    void shouldRejectInvalidMetricPayloads(String payload) {
        assertThrows(CorruptAnalysisCaseDataException.class, () -> EvidencePayloadCodec.decode(EvidenceType.METRIC, payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", ""})
    void shouldRejectUnknownTypeAndSourceCodes(String code) {
        var payload = "'nodes=INDEX_SCAN;estimatedRows=5'";
        assertThrows(CorruptAnalysisCaseDataException.class,
                () -> read(row(code, "POSTGRESQL_EXPLAIN", payload, "NULL"), mapper::read));
        assertThrows(CorruptAnalysisCaseDataException.class,
                () -> read(row("PLAN_SUMMARY", code, payload, "NULL"), mapper::read));
    }

    @Test
    void shouldRejectAPayloadWhoseTypeContradictsTheRowType() {
        assertThrows(CorruptAnalysisCaseDataException.class, () -> read(
                row("METRIC", "POSTGRESQL_EXPLAIN", "'nodes=INDEX_SCAN;estimatedRows=5'", "NULL"), mapper::read));
    }
}
