package com.vigil.adapters.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Feeds mappers a one-row ResultSet built from literals, so invalid values need no schema bypass. */
final class RowMapperTestSupport {
    private RowMapperTestSupport() {
    }

    interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    static <T> T read(String selectLiterals, RowReader<T> reader) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT " + selectLiterals)) {
            row.next();
            return reader.read(row);
        }
    }
}
