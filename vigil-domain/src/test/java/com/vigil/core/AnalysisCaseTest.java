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

    private static final UUID RECON_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant RECON_AT = Instant.parse("2026-08-26T12:00:00Z");

    private static com.vigil.core.domain.CaseEvent step(CaseStatus from, CaseStatus to) {
        return new com.vigil.core.domain.CaseEvent(from, to, "actor", "reason", RECON_AT.plusSeconds(60));
    }

    private static Evidence reconEvidence(UUID id, UUID caseId) {
        return new Evidence(id, caseId, EvidenceType.METRIC, EvidenceSource.PG_STAT_STATEMENTS, RECON_AT,
                "collector-v1", EvidenceConfidence.HIGH,
                new MetricEvidencePayload(MetricKind.CALLS, new BigDecimal("1.50"), MeasurementUnit.EXECUTIONS,
                        new ObservationWindow(RECON_AT.minusSeconds(60), RECON_AT)),
                Optional.empty());
    }

    private static AnalysisCase reconstitute(CaseStatus status, long version,
                                             List<com.vigil.core.domain.CaseEvent> events, List<Evidence> evidences) {
        return AnalysisCase.reconstitute(RECON_ID, RECON_AT, Optional.empty(), status, version, events, evidences);
    }

    @Test
    void shouldReconstituteEveryStatusWithoutPendingEventsAndKeepVersion() {
        var evidence = reconEvidence(UUID.randomUUID(), RECON_ID);
        var discovered = reconstitute(CaseStatus.DISCOVERED, 1, List.of(), List.of());
        var triaged = reconstitute(CaseStatus.TRIAGED, 2, List.of(step(CaseStatus.DISCOVERED, CaseStatus.TRIAGED)), List.of());
        var collected = reconstitute(CaseStatus.EVIDENCE_COLLECTED, 3, List.of(
                step(CaseStatus.DISCOVERED, CaseStatus.TRIAGED),
                step(CaseStatus.TRIAGED, CaseStatus.EVIDENCE_COLLECTED)), List.of(evidence));

        assertAll(
                () -> assertEquals(1, discovered.version()),
                () -> assertEquals(CaseStatus.TRIAGED, triaged.status()),
                () -> assertEquals(2, collected.events().size()),
                () -> assertEquals(List.of(evidence), collected.evidences()),
                () -> assertEquals(3, collected.version()),
                () -> assertTrue(collected.pendingEvents().isEmpty()),
                () -> assertTrue(collected.pullEvents().isEmpty())
        );
    }

    @Test
    void shouldContinueToWorkAfterReconstitution() {
        var triaged = reconstitute(CaseStatus.TRIAGED, 2, List.of(step(CaseStatus.DISCOVERED, CaseStatus.TRIAGED)), List.of());

        triaged.collectEvidence(reconEvidence(UUID.randomUUID(), RECON_ID), "actor", RECON_AT.plusSeconds(120));

        assertAll(
                () -> assertEquals(CaseStatus.EVIDENCE_COLLECTED, triaged.status()),
                () -> assertEquals(2, triaged.version()),
                () -> assertThrows(InvalidCaseTransitionException.class, () -> triaged.triage("r", "a", RECON_AT))
        );
    }

    static Stream<Arguments> invalidReconstitutions() {
        var id = UUID.randomUUID();
        var evidence = reconEvidence(id, RECON_ID);
        var toTriaged = step(CaseStatus.DISCOVERED, CaseStatus.TRIAGED);
        var toCollected = step(CaseStatus.TRIAGED, CaseStatus.EVIDENCE_COLLECTED);
        return Stream.of(
                Arguments.of("negative version", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.DISCOVERED, -1, List.of(), List.of())),
                Arguments.of("status ahead of history", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.TRIAGED, 1, List.of(), List.of())),
                Arguments.of("status behind history", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.DISCOVERED, 1, List.of(toTriaged), List.of())),
                Arguments.of("history skips a step", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.EVIDENCE_COLLECTED, 1, List.of(toCollected), List.of(evidence))),
                Arguments.of("history not starting at discovered", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.TRIAGED, 1, List.of(step(CaseStatus.TRIAGED, CaseStatus.TRIAGED)), List.of())),
                Arguments.of("backward transition", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.DISCOVERED, 1, List.of(toTriaged, step(CaseStatus.TRIAGED, CaseStatus.DISCOVERED)), List.of())),
                Arguments.of("blank actor", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.TRIAGED, 1, List.of(new com.vigil.core.domain.CaseEvent(
                                CaseStatus.DISCOVERED, CaseStatus.TRIAGED, " ", "r", RECON_AT)), List.of())),
                Arguments.of("evidence of another case", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.EVIDENCE_COLLECTED, 1, List.of(toTriaged, toCollected),
                                List.of(reconEvidence(id, UUID.randomUUID())))),
                Arguments.of("duplicate evidence ids", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.EVIDENCE_COLLECTED, 1, List.of(toTriaged, toCollected),
                                List.of(evidence, evidence))),
                Arguments.of("evidence before collection", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.TRIAGED, 1, List.of(toTriaged), List.of(evidence))),
                Arguments.of("collected without evidence", (java.util.function.Supplier<AnalysisCase>) () ->
                        reconstitute(CaseStatus.EVIDENCE_COLLECTED, 1, List.of(toTriaged, toCollected), List.of()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReconstitutions")
    void shouldRejectInconsistentReconstitution(String rule, java.util.function.Supplier<AnalysisCase> attempt) {
        assertThrows(IllegalArgumentException.class, attempt::get, rule);
    }

    @Test
    void shouldRejectNullReconstitutionInputs() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> AnalysisCase.reconstitute(
                        RECON_ID, RECON_AT, Optional.empty(), null, 0, List.of(), List.of())),
                () -> assertThrows(NullPointerException.class, () -> AnalysisCase.reconstitute(
                        RECON_ID, RECON_AT, Optional.empty(), CaseStatus.DISCOVERED, 0, null, List.of())),
                () -> assertThrows(NullPointerException.class, () -> AnalysisCase.reconstitute(
                        RECON_ID, RECON_AT, Optional.empty(), CaseStatus.DISCOVERED, 0, List.of(), null))
        );
    }

    @Test
    void shouldReadPendingEventsWithoutDrainingAndConfirmThemByIdAfterCommit() {
        var first = new DomainEventMetadata(UUID.randomUUID(), "actor", RECON_AT, "v1");
        var analysisCase = AnalysisCase.discover(RECON_ID, RECON_AT, first);
        var second = new DomainEventMetadata(UUID.randomUUID(), "actor", RECON_AT.plusSeconds(1), "v1");
        analysisCase.triage("reason", second);

        var pending = analysisCase.pendingEvents();
        assertEquals(2, pending.size());
        assertEquals(pending, analysisCase.pendingEvents());

        analysisCase.confirmPersisted(1, List.of(first.eventId()));

        assertAll(
                () -> assertEquals(1, analysisCase.version()),
                () -> assertEquals(List.of(pending.get(1)), analysisCase.pendingEvents()),
                () -> assertEquals(List.of(pending.get(1)), analysisCase.pullEvents())
        );
    }

    @Test
    void shouldNotChangeVersionBeforeConfirmationAndRejectRegression() {
        var analysisCase = AnalysisCase.discover(RECON_ID, RECON_AT);
        assertEquals(0, analysisCase.version());
        analysisCase.confirmPersisted(2, List.of());

        assertAll(
                () -> assertEquals(2, analysisCase.version()),
                () -> assertThrows(IllegalArgumentException.class, () -> analysisCase.confirmPersisted(1, List.of())),
                () -> assertThrows(NullPointerException.class, () -> analysisCase.confirmPersisted(3, null)),
                () -> assertEquals(2, analysisCase.version())
        );
    }
}
