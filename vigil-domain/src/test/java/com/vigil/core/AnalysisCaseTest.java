package com.vigil.core;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.exception.InvalidCaseTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AnalysisCaseTest {

    @Test
    void shouldTransitionFromDiscoveredToTriagedAndAppendAnAuditEvent() {
        // Given -> O que foi entregue?
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var createdAt = Instant.parse("2026-08-26T12:00:00Z");

        // When -> Quando X ação aconteceu
        var analysisCase = AnalysisCase.discover(caseId, createdAt);
        analysisCase.triage(
                "Consulta selecionada para investigação.",
                "vigil-core",
                Instant.parse("2026-08-26T12:01:00Z")
        );

        // Then -> O que deve obrigatoriamente ter acontecido?
        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(1, analysisCase.events().size())
        );

        var event = analysisCase.events().getFirst();

        assertAll(
                () -> assertEquals(CaseStatus.DISCOVERED, event.fromStatus()),
                () -> assertEquals(CaseStatus.TRIAGED, event.toStatus()),
                () -> assertEquals("vigil-core", event.actor()),
                () -> assertEquals("Consulta selecionada para investigação.", event.reason()),
                () -> assertEquals(
                        Instant.parse("2026-08-26T12:01:00Z"),
                        event.occuredAt()
                )
        );
    }

    @Test
    void shouldRejectTriagingACaseThatIsNoLongerDiscovered() {
        // Given -> O que foi entregue?
        var analysisCase = AnalysisCase.discover(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-08-26T12:00:00Z")
        );

        var transition = new TransitionMetadata(
                "vigil-core",
                "Consulta selecionada para investigação",
                Instant.parse("2026-08-26T12:01:00Z")
        );

        // When -> Quando X ação aconteceu
        analysisCase.triage(transition);

        // Then -> O que deve ser verdade?
        assertThrows(
                InvalidCaseTransitionException.class,
                () -> analysisCase.triage(transition)
        );

        assertEquals(1, analysisCase.events().size());
    }

    @Test
    void shouldRejectTransitionWhenReasonIsEmpty(){
        // Given -> O que foi entregue
        var occuredAt = Instant.parse("2026-08-26T12:01:00Z");

        // When + Then -> O que deve ser verdade?
        assertThrows(
                IllegalArgumentException.class,
                () -> new TransitionMetadata("vigil-core", "", occuredAt)
        );
    }

    @Test
    void shouldCollectCaseEvidenceThroughAnIntentionalTransition() {
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var analysisCase = AnalysisCase.discover(caseId, Instant.parse("2026-08-26T12:00:00Z"));
        analysisCase.triage("Investigar impacto absoluto.", "vigil-core", Instant.parse("2026-08-26T12:01:00Z"));
        var evidence = metricEvidence(caseId);

        analysisCase.collectEvidence(evidence, "vigil-core", Instant.parse("2026-08-26T12:02:00Z"));

        assertAll(
                () -> assertEquals(CaseStatus.EVIDENCE_COLLECTED, analysisCase.status()),
                () -> assertEquals(List.of(evidence), analysisCase.evidences()),
                () -> assertEquals(2, analysisCase.events().size()),
                () -> assertThrows(UnsupportedOperationException.class, () -> analysisCase.evidences().add(evidence))
        );
    }

    @Test
    void shouldRejectEvidenceFromAnotherCaseWithoutChangingTheAggregate() {
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var analysisCase = AnalysisCase.discover(caseId, Instant.parse("2026-08-26T12:00:00Z"));
        analysisCase.triage("Investigar impacto absoluto.", "vigil-core", Instant.parse("2026-08-26T12:01:00Z"));

        assertThrows(
                IllegalArgumentException.class,
                () -> analysisCase.collectEvidence(
                        metricEvidence(UUID.fromString("99999999-9999-9999-9999-999999999999")),
                        "vigil-core",
                        Instant.parse("2026-08-26T12:02:00Z")
                )
        );

        assertAll(
                () -> assertEquals(CaseStatus.TRIAGED, analysisCase.status()),
                () -> assertEquals(1, analysisCase.events().size()),
                () -> assertTrue(analysisCase.evidences().isEmpty())
        );
    }

    @Test
    void shouldRejectEvidenceCollectionBeforeTriageWithoutChangingTheAggregate() {
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var analysisCase = AnalysisCase.discover(caseId, Instant.parse("2026-08-26T12:00:00Z"));

        assertThrows(
                InvalidCaseTransitionException.class,
                () -> analysisCase.collectEvidence(
                        metricEvidence(caseId),
                        "vigil-core",
                        Instant.parse("2026-08-26T12:02:00Z")
                )
        );

        assertAll(
                () -> assertEquals(CaseStatus.DISCOVERED, analysisCase.status()),
                () -> assertTrue(analysisCase.events().isEmpty()),
                () -> assertTrue(analysisCase.evidences().isEmpty())
        );
    }

    private Evidence metricEvidence(UUID caseId) {
        return new Evidence(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                caseId,
                EvidenceType.METRIC,
                EvidenceSource.PG_STAT_STATEMENTS,
                Instant.parse("2026-08-26T12:01:30Z"),
                "collector-v1",
                EvidenceConfidence.HIGH,
                new MetricEvidencePayload(
                        MetricKind.TOTAL_EXECUTION_TIME,
                        BigDecimal.valueOf(60_000),
                        MeasurementUnit.MILLISECONDS,
                        new ObservationWindow(
                                Instant.parse("2026-08-26T12:00:00Z"),
                                Instant.parse("2026-08-26T12:01:00Z")
                        )
                ),
                Optional.empty()
        );
    }
}
