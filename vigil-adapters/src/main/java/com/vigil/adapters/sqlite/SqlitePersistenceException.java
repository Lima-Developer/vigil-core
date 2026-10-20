package com.vigil.adapters.sqlite;

/** Operational SQLite failure (I/O, busy database, unexpected constraint); never a version conflict. */
public final class SqlitePersistenceException extends RuntimeException {
    public SqlitePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
