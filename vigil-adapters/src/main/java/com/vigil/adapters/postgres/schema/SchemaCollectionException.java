package com.vigil.adapters.postgres.schema;

/**
 * The catalog collection failed for a reason other than missing privileges. The driver message can quote object
 * names, so neither it nor the cause is kept: only the SQLSTATE, which is safe.
 */
public final class SchemaCollectionException extends RuntimeException {
    private final String sqlState;

    SchemaCollectionException(String sqlState) {
        super("PostgreSQL catalog collection failed" + (sqlState == null ? "." : " (SQLSTATE " + sqlState + ")."));
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
