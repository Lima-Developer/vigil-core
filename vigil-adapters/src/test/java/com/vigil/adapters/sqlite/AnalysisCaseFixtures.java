package com.vigil.adapters.sqlite;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.core.domain.ProtectedEvidenceReference;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.RelationMetadataEvidencePayload;
import com.vigil.core.domain.SafeRelationReference;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.domain.enums.PlanNodeType;
import com.vigil.core.domain.interfaces.EvidencePayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builders for aggregates used by the SQLite adapter tests. */
final class AnalysisCaseFixtures {
    static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID EVIDENCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final Instant CREATED_AT = Instant.parse("2026-09-07T12:00:00.123456789Z");
    static final Instant LATER = Instant.parse("2026-09-07T12:01:00.000000001Z");

    private AnalysisCaseFixtures() {
    }

    static AnalysisCase discovered() {
        return AnalysisCase.discover(CASE_ID, CREATED_AT);
    }

    static AnalysisCase discoveredWithFingerprint(UUID caseId) {
        return AnalysisCase.reconstitute(caseId, CREATED_AT,
                Optional.of(new QueryFingerprint(
                        "hmac-sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "v1")),
                com.vigil.core.domain.enums.CaseStatus.DISCOVERED, 0, List.of(), List.of());
    }

    static AnalysisCase triaged() {
        var analysisCase = discovered();
        analysisCase.triage("Slow query.", "vigil-core", LATER);
        return analysisCase;
    }

    static AnalysisCase collected(EvidencePayload payload, Optional<ProtectedEvidenceReference> reference) {
        var analysisCase = triaged();
        analysisCase.collectEvidence(evidence(payload, reference), "collector", LATER.plusSeconds(1));
        return analysisCase;
    }

    static Evidence evidence(EvidencePayload payload, Optional<ProtectedEvidenceReference> reference) {
        return new Evidence(EVIDENCE_ID, CASE_ID, payload.type(), EvidenceSource.POSTGRESQL_EXPLAIN,
                Instant.parse("2026-09-07T12:00:30.999999999Z"), "collector-v1", EvidenceConfidence.MEDIUM,
                payload, reference);
    }

    static MetricEvidencePayload metric() {
        return new MetricEvidencePayload(MetricKind.TOTAL_EXECUTION_TIME, new BigDecimal("60000.500"),
                MeasurementUnit.MILLISECONDS,
                new ObservationWindow(Instant.parse("2026-09-07T11:00:00.000000001Z"), CREATED_AT));
    }

    static PlanSummaryEvidencePayload plan() {
        return new PlanSummaryEvidencePayload(List.of(PlanNodeType.SEQUENTIAL_SCAN, PlanNodeType.HASH_JOIN), 1_000_000L);
    }

    static RelationMetadataEvidencePayload relation() {
        return new RelationMetadataEvidencePayload(new SafeRelationReference("relation_orders"), 42L, 3, true);
    }

    static EvidenceType typeOf(EvidencePayload payload) {
        return payload.type();
    }
}
