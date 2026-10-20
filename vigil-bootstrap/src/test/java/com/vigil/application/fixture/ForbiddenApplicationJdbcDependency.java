package com.vigil.application.fixture;

import java.sql.Connection;

/** Deliberately violates the rule that keeps JDBC out of the application layer. Used only by ArchitectureTest. */
public class ForbiddenApplicationJdbcDependency {

    public Connection leakedConnection() {
        return null;
    }
}
