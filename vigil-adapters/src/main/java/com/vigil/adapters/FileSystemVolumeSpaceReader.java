package com.vigil.adapters;

import com.vigil.application.port.out.interfaces.LocalVolumeSpaceReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/** Does not create probe files, connect to a target or expose the host path. */
public final class FileSystemVolumeSpaceReader implements LocalVolumeSpaceReader {
    @Override
    public OptionalLong usableBytes(Path databaseFile) {
        try {
            Path directory = databaseFile.toAbsolutePath().getParent();
            if (directory == null || !Files.isDirectory(directory)) return OptionalLong.empty();
            long bytes = Files.getFileStore(directory).getUsableSpace();
            return bytes < 0 ? OptionalLong.empty() : OptionalLong.of(bytes);
        } catch (IOException | SecurityException failure) {
            return OptionalLong.empty();
        }
    }
}
