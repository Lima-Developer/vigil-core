package com.vigil.application.port.out.interfaces;

import java.nio.file.Path;

public interface StorageProbe {
    StorageFacts inspect(Path databaseFile);
}
