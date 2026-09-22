package com.vigil.core;

import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.enums.CaseStatus;
import com.vigil.core.domain.TransitionMetadata;
import com.vigil.core.exception.InvalidCaseTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AnalysisCaseTest {

    @Test
    void shouldTransitionFromDiscoveredToTriagedAndAppendAnAuditEvent() {
        // Given -> O que foi entregue?
        var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var createdAt = Instant.parse("2026-08-26T12:00:00Z");

        var analysisCase = AnalysisCase.discovered(caseId, createdAt);
        var transition = new TransitionMetadata(
                "vigil-core",
                "Consulta selecionada para investigação.",
                Instant.parse("2026-08-26T12:01:00Z")
        );

        // When -> Quando X ação aconteceu
        analysisCase.triage(transition);

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
        var analysisCase = AnalysisCase.discovered(
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
}
