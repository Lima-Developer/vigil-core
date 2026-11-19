package com.vigil.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EphemeralDemoStorageTest {
    @Test
    void allocatesAndDeletesOnlyForAnExplicitDemoWithoutConfiguredStorage() {
        var storage = EphemeralDemoStorage.prepare(new String[]{"--vigil.demo.ephemeral=true"}, Map.of()).orElseThrow();
        assertTrue(storage.databaseFile().isAbsolute());
        var directory = storage.databaseFile().getParent();

        storage.close();

        assertFalse(Files.exists(directory));
    }

    @Test
    void preservesExplicitDatabaseConfiguration() {
        assertTrue(EphemeralDemoStorage.prepare(new String[]{"--vigil.demo.ephemeral=true"},
                Map.of("VIGIL_DATABASE_PATH", "C:\\vigil\\persistent.db")).isEmpty());
    }

    @Test
    void recognizesOnlyExplicitDemoRequestsAndAllPersistentOverrides() {
        assertTrue(EphemeralDemoStorage.prepare(new String[0], Map.of()).isEmpty());
        var fromProfile = EphemeralDemoStorage.prepare(new String[]{"--spring.profiles.active=test,demo"},
                Map.of("VIGIL_DATABASE_PATH", " ")).orElseThrow();
        var fromDemoProfile = EphemeralDemoStorage.prepare(new String[]{"--spring.profiles.active=demo"},
                Map.of("VIGIL_DATABASE_PATH", " ")).orElseThrow();
        assertTrue(EphemeralDemoStorage.prepare(new String[]{"--vigil.demo.ephemeral=true", "--vigil.sqlite.path=/tmp/persistent.db"},
                Map.of()).isEmpty());
        var fromEnvironment = EphemeralDemoStorage.prepare(new String[0], Map.of("VIGIL_DEMO_MODE", "TRUE")).orElseThrow();

        assertTrue(fromProfile.databaseFile().isAbsolute());
        assertTrue(fromDemoProfile.databaseFile().isAbsolute());
        assertTrue(fromEnvironment.databaseFile().isAbsolute());
        fromProfile.close();
        fromDemoProfile.close();
        fromEnvironment.close();
    }
}
