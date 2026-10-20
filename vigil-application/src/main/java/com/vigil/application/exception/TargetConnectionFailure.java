package com.vigil.application.exception;

import java.util.Objects;
import java.util.Optional;

/**
 * The only error that crosses the boundary of the target adapter. The message carries the target name, the reason
 * and the SQLSTATE, never a URL, host, user or secret. The original cause is kept for internal logging only.
 */
public final class TargetConnectionFailure extends RuntimeException {
    private final TargetFailureReason reason;
    private final String targetName;
    private final String sqlState;

    public TargetConnectionFailure(TargetFailureReason reason, String targetName, String sqlState, Throwable cause) {
        super(message(reason, targetName, sqlState), cause);
        this.reason = reason;
        this.targetName = targetName;
        this.sqlState = sqlState;
    }

    private static String message(TargetFailureReason reason, String targetName, String sqlState) {
        Objects.requireNonNull(reason, "Motivo da falha não pode ser nulo");
        Objects.requireNonNull(targetName, "Nome do target não pode ser nulo");
        var message = "Falha ao acessar o target '" + targetName + "': " + reason;
        return sqlState == null ? message : message + " (SQLSTATE " + sqlState + ")";
    }

    public TargetFailureReason reason() {
        return reason;
    }

    public String targetName() {
        return targetName;
    }

    public Optional<String> sqlState() {
        return Optional.ofNullable(sqlState);
    }
}
