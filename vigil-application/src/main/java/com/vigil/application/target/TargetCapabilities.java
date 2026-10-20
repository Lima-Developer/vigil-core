package com.vigil.application.target;

import java.time.Instant;
import java.util.Objects;

public record TargetCapabilities(String serverVersion, boolean pgStatStatementsInstalled, Instant checkedAt) {
    public TargetCapabilities {
        Objects.requireNonNull(serverVersion, "Versão do servidor não pode ser nula");
        Objects.requireNonNull(checkedAt, "Instante da verificação não pode ser nulo");
        if (serverVersion.isBlank()) {
            throw new IllegalArgumentException("Versão do servidor não pode ser vazia");
        }
    }
}
