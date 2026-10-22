package com.vigil.adapters.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Strict decoding of persisted columns; any invalid value is reported as corrupt data, never defaulted. */
final class PersistedValues {

    private PersistedValues() {
    }

    static String text(ResultSet row, String column) throws SQLException {
        var value = row.getString(column);
        if (value == null) {
            throw new CorruptAnalysisCaseDataException("Column %s must not be null.".formatted(column));
        }
        return value;
    }

    static String optionalText(ResultSet row, String column) throws SQLException {
        return row.getString(column);
    }

    static long number(ResultSet row, String column) throws SQLException {
        var value = row.getLong(column);
        if (row.wasNull()) {
            throw new CorruptAnalysisCaseDataException("Column %s must not be null.".formatted(column));
        }
        return value;
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return uuid(text(row, column), column);
    }

    static UUID uuid(String text, String column) {
        try {
            var uuid = UUID.fromString(text);
            if (!uuid.toString().equals(text)) {
                throw new IllegalArgumentException("not canonical");
            }
            return uuid;
        } catch (IllegalArgumentException invalid) {
            throw new CorruptAnalysisCaseDataException("Column %s is not a canonical UUID.".formatted(column), invalid);
        }
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        try {
            return SqliteInstantCodec.decode(text(row, column));
        } catch (IllegalArgumentException invalid) {
            throw new CorruptAnalysisCaseDataException("Column %s is not a canonical instant.".formatted(column), invalid);
        }
    }

    static <E extends Enum<E>> E code(Class<E> type, String text, String column) {
        for (var constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        throw new CorruptAnalysisCaseDataException("Column %s holds an unknown %s code."
                .formatted(column, type.getSimpleName()));
    }

    static <E extends Enum<E>> E code(ResultSet row, Class<E> type, String column) throws SQLException {
        return code(type, text(row, column), column);
    }
}
