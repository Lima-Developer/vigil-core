package com.vigil.application.port.out.interfaces;

import java.nio.file.Path;
import java.util.OptionalLong;

/** Read-only observation of the volume containing Vigil's local database. */
@FunctionalInterface
public interface LocalVolumeSpaceReader {
    OptionalLong usableBytes(Path databaseFile);
}
