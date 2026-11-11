package com.vigil.bootstrap;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class DemoCaseControllerTest {
    @TempDir Path directory;

    @Test void createsReadsAndTriagesOnePersistedCase() {
        new ApplicationContextRunner().withUserConfiguration(VigilApplication.class)
                .withPropertyValues("spring.main.web-application-type=none", "vigil.target.name=lab",
                        "vigil.target.environment=test", "vigil.target.host=localhost",
                        "vigil.target.database=postgres", "vigil.sqlite.path=" + directory.resolve("case.db"),
                        "vigil.sqlite.minimum-free-bytes=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var api = context.getBean(DemoCaseController.class);
                    assertThrows(ResponseStatusException.class, api::show);
                    var created = api.create();
                    assertEquals("DISCOVERED", created.status());
                    assertEquals(created.id(), api.create().id());
                    assertEquals(10, created.metrics().calls());
                    assertTrue(created.explain().estimated());
                    assertFalse(created.events().isEmpty());
                    assertThrows(ResponseStatusException.class,
                            () -> api.triage(created.id(), new DemoCaseController.TriageRequest("curto")));
                    assertThrows(ResponseStatusException.class,
                            () -> api.triage(java.util.UUID.randomUUID(), new DemoCaseController.TriageRequest("motivo suficiente")));
                    var triaged = api.triage(created.id(), new DemoCaseController.TriageRequest("Prioridade para análise de índice"));
                    assertEquals("TRIAGED", triaged.status());
                    assertTrue(triaged.events().size() > created.events().size());
                    assertEquals("TRIAGED", api.show().status());
                    assertThrows(com.vigil.application.exception.InvalidAnalysisCaseTransition.class,
                            () -> api.triage(created.id(), new DemoCaseController.TriageRequest("Motivo duplicado")));
                });
    }
}
