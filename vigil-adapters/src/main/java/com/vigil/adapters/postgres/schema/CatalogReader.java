package com.vigil.adapters.postgres.schema;

import com.vigil.adapters.postgres.schema.CatalogSnapshot.ColumnRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.ConstraintRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.IndexRow;
import com.vigil.adapters.postgres.schema.CatalogSnapshot.RelationRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Runs the catalog queries on a connection that is already read-only and inside a transaction. */
final class CatalogReader {
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private CatalogReader() {
    }

    static CatalogSnapshot read(Connection connection, List<RelationSelector> selectors) throws SQLException {
        var relations = new ArrayList<RelationRow>();
        String[] schemas = selectors.stream().map(RelationSelector::schema).toArray(String[]::new);
        String[] names = selectors.stream().map(RelationSelector::name).toArray(String[]::new);
        query(connection, CatalogQueries.RELATIONS,
                statement -> {
                    statement.setArray(1, connection.createArrayOf("text", schemas));
                    statement.setArray(2, connection.createArrayOf("text", names));
                },
                result -> relations.add(new RelationRow(result.getLong(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getDouble(5), result.getLong(6))));
        if (relations.isEmpty()) {
            return new CatalogSnapshot(List.of(), List.of(), List.of(), List.of());
        }
        Long[] oids = relations.stream().map(RelationRow::oid).toArray(Long[]::new);
        ParameterBinder bindOids = statement -> statement.setArray(1, connection.createArrayOf("int8", oids));

        var columns = new ArrayList<ColumnRow>();
        query(connection, CatalogQueries.COLUMNS, bindOids,
                result -> columns.add(new ColumnRow(result.getLong(1), result.getInt(2), result.getString(3),
                        result.getString(4), result.getBoolean(5))));

        var constraints = new ArrayList<ConstraintRow>();
        query(connection, CatalogQueries.CONSTRAINTS, bindOids,
                result -> constraints.add(new ConstraintRow(result.getLong(1), result.getString(2),
                        attnums(result.getString(3)))));

        // Keyed by indexrelid: an index is reported once however many rows or joins reach it.
        var indexes = new LinkedHashMap<Long, IndexRow>();
        query(connection, CatalogQueries.INDEXES, bindOids,
                result -> indexes.putIfAbsent(result.getLong(2), new IndexRow(result.getLong(1), result.getLong(2),
                        result.getString(3), result.getString(4), result.getBoolean(5), result.getBoolean(6),
                        result.getBoolean(7), result.getBoolean(8), result.getInt(9),
                        attnums(result.getString(10)))));
        return new CatalogSnapshot(relations, columns, constraints, List.copyOf(indexes.values()));
    }

    private static void query(Connection connection, String sql, ParameterBinder binder, RowConsumer rows)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.accept(result);
                }
            }
        }
    }

    private static List<Integer> attnums(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank()) {
            return List.of();
        }
        var attnums = new ArrayList<Integer>();
        for (String token : spaceSeparated.trim().split(" +")) {
            attnums.add(Integer.parseInt(token));
        }
        return attnums;
    }

    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet result) throws SQLException;
    }
}
