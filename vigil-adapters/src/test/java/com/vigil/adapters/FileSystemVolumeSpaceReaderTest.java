package com.vigil.adapters;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileSystemVolumeSpaceReaderTest {
    @TempDir Path directory;

    @Test void readsVolumeWithoutCreatingTheDatabase() {
        var file = directory.resolve("not-created.db");
        var space = new FileSystemVolumeSpaceReader().usableBytes(file);
        assertTrue(space.isPresent());
        assertTrue(space.getAsLong() >= 0);
        assertFalse(Files.exists(file));
    }

    @Test void missingVolumeIsNotReportedAsZero() {
        assertTrue(new FileSystemVolumeSpaceReader()
                .usableBytes(directory.resolve("missing/database.db")).isEmpty());
    }
}
