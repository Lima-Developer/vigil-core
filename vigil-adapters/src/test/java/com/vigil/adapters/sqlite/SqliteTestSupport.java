package com.vigil.adapters.sqlite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Helpers for tests that inspect real SQLite files. */
public final class SqliteTestSupport {

    private SqliteTestSupport() {
    }

    public static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Returns every row of {@code sql} as its columns joined by {@code " | "}. */
    public static List<String> rows(Connection connection, String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            var columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                var values = new ArrayList<String>();
                for (int column = 1; column <= columns; column++) {
                    values.add(String.valueOf(result.getString(column)));
                }
                rows.add(String.join(" | ", values));
            }
        }
        return rows;
    }

    /** Schema definitions followed by the content of every table, used to prove a database did not change. */
    public static List<String> snapshot(Connection connection) throws SQLException {
        var snapshot = new ArrayList<>(rows(connection, "SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY name"));
        for (var table : rows(connection, "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")) {
            var quoted = '"' + table.replace("\"", "\"\"") + '"';
            for (var row : rows(connection, "SELECT * FROM " + quoted + " ORDER BY rowid")) {
                snapshot.add(table + ": " + row);
            }
        }
        return snapshot;
    }

    /** Wraps {@code delegate} so that calls matching {@code fails} throw instead of reaching the driver. */
    public static Connection failing(Connection delegate, BiPredicate<String, Object[]> fails) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (fails.test(method.getName(), arguments)) {
                        throw new SQLException("Injected " + method.getName() + " failure.");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException invocationFailure) {
                        throw invocationFailure.getCause();
                    }
                }
        );
    }
}
