package com.vigil.adapters.postgres.schema;

import java.util.List;

/**
 * Raw catalog rows, in memory only and holding real names until the assembler pseudonymizes them. Their
 * {@code toString} is redacted so a stray log line or exception cannot print a name.
 */
record CatalogSnapshot(
        List<RelationRow> relations,
        List<ColumnRow> columns,
        List<ConstraintRow> constraints,
        List<IndexRow> indexes
) {
    record RelationRow(long oid, String schema, String name, String relkind, double reltuples, long relpages) {
        @Override
        public String toString() {
            return "RelationRow[redacted]";
        }
    }

    record ColumnRow(long relationOid, int attnum, String name, String sqlType, boolean nullable) {
        @Override
        public String toString() {
            return "ColumnRow[redacted]";
        }
    }

    record ConstraintRow(long relationOid, String contype, List<Integer> attnums) {
    }

    /** {@code attnums} holds every indkey position (0 = expression); the first {@code keyCount} are the keys. */
    record IndexRow(long relationOid, long indexOid, String name, String accessMethod, boolean unique,
                    boolean primary, boolean valid, boolean partial, int keyCount, List<Integer> attnums) {
        @Override
        public String toString() {
            return "IndexRow[redacted]";
        }
    }
}
