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
}
