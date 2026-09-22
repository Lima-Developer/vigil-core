package com.vigil.core;

import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.ProtectedEvidenceReference;
import com.vigil.core.domain.RelationMetadataEvidencePayload;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.enums.PlanNodeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceTest {

    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant CAPTURED_AT = Instant.parse("2026-09-07T12:05:00Z");

    @Test
    void shouldCreateMetricEvidenceWithExplicitProvenanceAndUnit() {
        var evidence = metricEvidence();

        assertAll(
                () -> assertEquals(CASE_ID, evidence.caseId()),
                () -> assertEquals(EvidenceType.METRIC, evidence.type()),
                () -> assertEquals(EvidenceSource.PG_STAT_STATEMENTS, evidence.source()),
                () -> assertEquals(CAPTURED_AT, evidence.capturedAt()),
                () -> assertEquals("collector-v1", evidence.collectorVersion()),
                () -> assertEquals(EvidenceConfidence.HIGH, evidence.confidence()),
                () -> assertEquals(MeasurementUnit.MILLISECONDS, ((MetricEvidencePayload) evidence.safePayload()).unit())
        );
    }

    @Test
    void shouldRejectMetricWhenUnitDoesNotMatchItsKind() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricEvidencePayload(
                        MetricKind.TOTAL_EXECUTION_TIME,
                        BigDecimal.ONE,
                        MeasurementUnit.ROWS,
                        observationWindow()
                )
        );
    }

    @Test
    void shouldRejectEvidenceWithoutSourceOrCaptureTime() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> evidenceWith(null, CAPTURED_AT)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> evidenceWith(EvidenceSource.PG_STAT_STATEMENTS, null)
                )
        );
    }

    @Test
    void shouldUseExclusiveStartAndInclusiveEndForObservationWindow() {
        var window = observationWindow();

        assertAll(
                () -> assertFalse(window.contains(Instant.parse("2026-09-07T12:00:00Z"))),
                () -> assertTrue(window.contains(CAPTURED_AT))
        );
    }

    @Test
    void shouldKeepPlanSummaryImmutable() {
        var nodes = new ArrayList<>(List.of(PlanNodeType.SEQUENTIAL_SCAN));
        var payload = new PlanSummaryEvidencePayload(nodes, 1_000);
        nodes.add(PlanNodeType.INDEX_SCAN);

        assertAll(
                () -> assertEquals(List.of(PlanNodeType.SEQUENTIAL_SCAN), payload.nodeTypes()),
                () -> assertThrows(UnsupportedOperationException.class, () -> payload.nodeTypes().add(PlanNodeType.INDEX_SCAN))
        );
    }

    @Test
    void shouldRejectPayloadWithDifferentEvidenceType() {
        var payload = new RelationMetadataEvidencePayload(
                new SafeRelationReference("relation_1"),
                100_000,
                0,
                false
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new Evidence(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        CASE_ID,
                        EvidenceType.METRIC,
                        EvidenceSource.POSTGRESQL_CATALOG,
                        CAPTURED_AT,
                        "collector-v1",
                        EvidenceConfidence.MEDIUM,
                        payload,
                        Optional.empty()
                )
        );
    }

    @Test
    void shouldRejectLinkingEvidenceToAnotherCase() {
        var evidence = metricEvidence();

        assertThrows(
                IllegalArgumentException.class,
                () -> evidence.requireBelongsTo(UUID.fromString("99999999-9999-9999-9999-999999999999"))
        );
    }

    @Test
    void shouldRedactProtectedReferenceInTextRepresentations() {
        var reference = new ProtectedEvidenceReference("vault://evidence/plan-42");

        assertEquals("ProtectedEvidenceReference[redacted]", reference.toString());
    }

    private Evidence metricEvidence() {
        return evidenceWith(EvidenceSource.PG_STAT_STATEMENTS, CAPTURED_AT);
    }

    private Evidence evidenceWith(EvidenceSource source, Instant capturedAt) {
        return new Evidence(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                CASE_ID,
                EvidenceType.METRIC,
                source,
                capturedAt,
                "collector-v1",
                EvidenceConfidence.HIGH,
                new MetricEvidencePayload(
                        MetricKind.TOTAL_EXECUTION_TIME,
                        BigDecimal.valueOf(60000),
                        MeasurementUnit.MILLISECONDS,
                        observationWindow()
                ),
                Optional.of(new ProtectedEvidenceReference("vault://evidence/metric-42"))
        );
    }

    private ObservationWindow observationWindow() {
        return new ObservationWindow(
                Instant.parse("2026-09-07T12:00:00Z"),
                CAPTURED_AT
        );
    }
}
