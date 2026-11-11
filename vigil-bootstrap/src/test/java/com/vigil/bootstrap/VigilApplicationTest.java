package com.vigil.bootstrap;

import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class VigilApplicationTest {
    @TempDir Path directory;

    @Test void testProfileStartsComposedContextAndClosesIt() {
        var database = directory.resolve("vigil.db");
        new ApplicationContextRunner()
                .withUserConfiguration(VigilApplication.class)
                .withPropertyValues("spring.profiles.active=test", "spring.main.web-application-type=none",
                        "vigil.target.name=test", "vigil.target.environment=test",
                        "vigil.target.host=localhost", "vigil.target.database=postgres",
                        "vigil.sqlite.path=" + database, "vigil.sqlite.minimum-free-bytes=1",
                        "vigil.inference.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(AnalysisCaseRepository.class));
                    assertNotNull(context.getBean(AnalysisCaseAuditReader.class));
                    assertNotNull(context.getBean(DiscoverObservedQueryUseCase.class));
                    assertNotNull(context.getBean(TriageAnalysisCaseUseCase.class));
                    assertFalse(context.getBean(VigilProperties.class).inference().enabled());
                    assertTrue(java.nio.file.Files.exists(database));
                });
    }
}
