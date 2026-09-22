package com.vigil.core;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.AnalysisCaseDiscovered;
import com.vigil.core.domain.DomainEventMetadata;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.EvidenceCollected;
import com.vigil.core.domain.MetricEvidencePayload;
import com.vigil.core.domain.ObservationWindow;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.domain.QueryTriaged;
import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;
import com.vigil.core.domain.enums.MeasurementUnit;
import com.vigil.core.domain.enums.MetricKind;
import com.vigil.core.exception.InvalidCaseTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidTransitions")
    void shouldRejectInvalidTransitionsWithoutSideEffects(
            String description,
            Consumer<AnalysisCase> arrange,
            Consumer<AnalysisCase> invalidTransition
    ) {
        var analysisCase = AnalysisCase.discover(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-08-26T12:00:00Z")
        );
        arrange.accept(analysisCase);
        var statusBefore = analysisCase.status();
        var eventsBefore = analysisCase.events();
        var evidencesBefore = analysisCase.evidences();

        assertThrows(InvalidCaseTransitionException.class, () -> invalidTransition.accept(analysisCase));

        assertAll(
                () -> assertEquals(statusBefore, analysisCase.status()),
                () -> assertEquals(eventsBefore, analysisCase.events()),
                () -> assertEquals(evidencesBefore, analysisCase.evidences())
        );
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

    @Test
    void shouldPublishSafeDomainEventsAndClearOnlyThePendingQueue() {
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var discoveredAt = Instant.parse("2026-08-26T12:00:00Z");
        var analysisCase = AnalysisCase.discover(
                caseId,
                discoveredAt,
                eventMetadata("33333333-3333-3333-3333-333333333333", discoveredAt)
        );
        analysisCase.triage(
                "Investigar impacto absoluto.",
                eventMetadata("44444444-4444-4444-4444-444444444444", Instant.parse("2026-08-26T12:01:00Z"))
        );
        analysisCase.collectEvidence(
                metricEvidence(caseId),
                eventMetadata("55555555-5555-5555-5555-555555555555", Instant.parse("2026-08-26T12:02:00Z"))
        );

        var events = analysisCase.pullEvents();

        assertAll(
                () -> assertEquals(3, events.size()),
                () -> assertInstanceOf(AnalysisCaseDiscovered.class, events.get(0)),
                () -> assertInstanceOf(QueryTriaged.class, events.get(1)),
                () -> assertInstanceOf(EvidenceCollected.class, events.get(2)),
                () -> assertEquals(caseId, events.get(2).caseId()),
                () -> assertTrue(analysisCase.pullEvents().isEmpty())
        );
    }

    private static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(
                        "TRIAGED não pode ser triaged novamente",
                        (Consumer<AnalysisCase>) analysisCase -> analysisCase.triage(
                                "Investigar impacto absoluto.", "vigil-core", Instant.parse("2026-08-26T12:01:00Z")
                        ),
                        (Consumer<AnalysisCase>) analysisCase -> analysisCase.triage(
                                "Repetir triagem.", "vigil-core", Instant.parse("2026-08-26T12:02:00Z")
                        )
                ),
                Arguments.of(
                        "DISCOVERED não pode coletar evidência",
                        (Consumer<AnalysisCase>) analysisCase -> { },
                        (Consumer<AnalysisCase>) analysisCase -> analysisCase.collectEvidence(
                                metricEvidence(analysisCase.caseId()),
                                "vigil-core",
                                Instant.parse("2026-08-26T12:02:00Z")
                        )
                )
        );
    }

    private static DomainEventMetadata eventMetadata(String eventId, Instant occurredAt) {
        return new DomainEventMetadata(UUID.fromString(eventId), "vigil-core", occurredAt, "v1");
    }

    private static Evidence metricEvidence(UUID caseId) {
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
