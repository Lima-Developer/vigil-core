package com.vigil.bootstrap;

import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test void localProfileStartsWithoutConnectingToPostgres() {
        var database = directory.resolve("local.db");
        runner(database).withPropertyValues("spring.profiles.active=local").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBeanDefinition("postgresCapabilityProbe"));
        });
    }

    @Test void missingStorageDirectoryPreventsStartupBeforeMigration() {
        var database = directory.resolve("missing/vigil.db");
        runner(database).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "prefer", "require"})
    void insecureSslModesFailBeforeCreatingStorage(String mode) {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.target.ssl-mode=" + mode).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @Test void invalidInferenceConfigurationFailsBeforeCreatingStorage() {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.inference.enabled=true").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0.5S", "PT61S"})
    void inferenceTimeoutOutsideSupportedBoundsFailsBinding(String timeout) {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.inference.timeout=" + timeout).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveSqliteFreeSpaceFailsBeforeCreatingStorage(String minimum) {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.sqlite.minimum-free-bytes=" + minimum).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1"})
    void outOfRangeTargetPortFailsBeforeCreatingStorage(String port) {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.target.port=" + port).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @Test void missingRequiredTargetPropertyFailsBeforeCreatingStorage() {
        var database = directory.resolve("vigil.db");
        new ApplicationContextRunner().withUserConfiguration(VigilApplication.class)
                .withPropertyValues("spring.profiles.active=test", "vigil.target.name=test",
                        "vigil.target.environment=test", "vigil.target.database=postgres",
                        "vigil.sqlite.path=" + database,
                        "vigil.sqlite.minimum-free-bytes=1", "vigil.inference.enabled=false")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(java.nio.file.Files.exists(database));
                    assertTrue(rootMessage(context.getStartupFailure()).contains("vigil.target.host"));
                });
    }

    @Test void invalidTargetBindingDoesNotEchoConfiguredSecret() {
        var secret = "senha-super-secreta";
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("vigil.target.port=" + secret).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(rootMessage(context.getStartupFailure()).contains(secret));
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    @Test void multipleTargetsFailBeforeCreatingStorage() {
        var database = directory.resolve("vigil.db");
        runner(database).withPropertyValues("VIGIL_TARGET_1_HOST=other").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertFalse(java.nio.file.Files.exists(database));
        });
    }

    private ApplicationContextRunner runner(Path database) {
        return new ApplicationContextRunner().withUserConfiguration(VigilApplication.class)
                .withPropertyValues("spring.profiles.active=test", "vigil.target.name=test",
                        "vigil.target.environment=test", "vigil.target.host=localhost",
                        "vigil.target.database=postgres", "vigil.sqlite.path=" + database,
                        "vigil.sqlite.minimum-free-bytes=1", "vigil.inference.enabled=false");
    }

    private static String rootMessage(Throwable failure) {
        var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
