package com.vigil.adapters.postgres.schema;

/**
 * Constant, parameterized, catalog-only SQL. No statement touches a user table (so there is no {@code COUNT(*)} and
 * no business data), and none selects index predicate, index definition or CHECK expression text: a predicate is
 * reduced to {@code indpred IS NOT NULL}.
 */
final class CatalogQueries {
    /** Parameters: schema names and relation names, as two parallel text arrays. */
    static final String RELATIONS = "SELECT c.oid::bigint, n.nspname, c.relname, c.relkind::text,"
            + " c.reltuples::float8, c.relpages::bigint"
            + " FROM unnest(?::text[], ?::text[]) AS wanted(schema_name, relation_name)"
            + " JOIN pg_namespace n ON n.nspname = wanted.schema_name"
            + " JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = wanted.relation_name"
            + " WHERE c.relkind IN ('r', 'm', 'p', 'f')"
            + " ORDER BY n.nspname, c.relname";

    /** Parameter: relation oids. Types outside pg_catalog may have sensitive names and are reported generically. */
    static final String COLUMNS = "SELECT a.attrelid::bigint, a.attnum::int, a.attname,"
            + " CASE WHEN t.typnamespace = 'pg_catalog'::regnamespace"
            + " THEN format_type(a.atttypid, a.atttypmod) ELSE 'user_defined' END,"
            + " NOT a.attnotnull"
            + " FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid"
            + " WHERE a.attrelid::bigint = ANY(?::bigint[]) AND a.attnum > 0 AND NOT a.attisdropped"
            + " ORDER BY a.attrelid, a.attnum";

    /** Parameter: relation oids. Only kind and key columns; never the constraint name or its definition. */
    static final String CONSTRAINTS = "SELECT c.conrelid::bigint, c.contype::text,"
            + " coalesce(array_to_string(c.conkey, ' '), '')"
            + " FROM pg_constraint c"
            + " WHERE c.conrelid::bigint = ANY(?::bigint[]) AND c.contype IN ('p', 'u', 'f', 'c', 'x')"
            + " ORDER BY c.conrelid, c.oid";

    /** Parameter: relation oids. One row per indexrelid by construction. */
    static final String INDEXES = "SELECT i.indrelid::bigint, i.indexrelid::bigint, ic.relname, am.amname,"
            + " i.indisunique, i.indisprimary, i.indisvalid, i.indpred IS NOT NULL,"
            + " i.indnkeyatts::int, i.indkey::text"
            + " FROM pg_index i"
            + " JOIN pg_class ic ON ic.oid = i.indexrelid"
            + " JOIN pg_am am ON am.oid = ic.relam"
            + " WHERE i.indrelid::bigint = ANY(?::bigint[])"
            + " ORDER BY i.indrelid, ic.relname";

    private CatalogQueries() {
    }
}
