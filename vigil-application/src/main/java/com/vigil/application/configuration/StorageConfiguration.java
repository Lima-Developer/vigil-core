package com.vigil.application.configuration;

import java.nio.file.Path;
import java.util.Objects;

public record StorageConfiguration(Path databaseFile, long minimumFreeBytes) {
    public StorageConfiguration {
        Objects.requireNonNull(databaseFile, "Caminho do banco não pode ser nulo");
        if (!databaseFile.isAbsolute()) {
            throw new IllegalArgumentException("Caminho do banco deve ser absoluto: " + databaseFile);
        }
        if (minimumFreeBytes <= 0) {
            throw new IllegalArgumentException("Espaço mínimo deve ser positivo: " + minimumFreeBytes);
        }
        databaseFile = databaseFile.normalize();
    }
}
