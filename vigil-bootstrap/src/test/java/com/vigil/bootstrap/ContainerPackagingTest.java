package com.vigil.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerPackagingTest {
    @Test
    void dockerImageDeclaresPersistentSqliteVolumeAndExternalConfiguration() throws Exception {
        Path root = repositoryRoot();
        String dockerfile = Files.readString(root.resolve("Dockerfile"));
        assertAll(
                () -> assertTrue(dockerfile.contains("VOLUME [\"/var/lib/vigil\"]")),
                () -> assertTrue(dockerfile.contains("VIGIL_DATABASE_PATH=/var/lib/vigil/vigil.db")),
                () -> assertTrue(dockerfile.contains("USER 10001"))
        );
    }

    @Test
    void composeContainsVigilSyntheticPostgresAndNamedSqliteVolume() throws Exception {
        Path root = repositoryRoot();
        String compose = Files.readString(root.resolve("compose.yml"));
        assertAll(
                () -> assertTrue(compose.contains("postgres:16.4")),
                () -> assertTrue(compose.contains("vigil-sqlite:/var/lib/vigil")),
                () -> assertTrue(compose.contains("./docker/demo/init.sql")),
                () -> assertTrue(Files.readString(root.resolve("docker/demo/init.sql")).contains("lab_orders"))
        );
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("vigil-bootstrap"))
                ? current : current.getParent();
    }
}
