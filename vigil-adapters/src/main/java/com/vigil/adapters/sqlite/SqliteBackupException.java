package com.vigil.adapters.sqlite;

public final class SqliteBackupException extends RuntimeException {
    public SqliteBackupException(String message) {
        super(message);
    }

    public SqliteBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
