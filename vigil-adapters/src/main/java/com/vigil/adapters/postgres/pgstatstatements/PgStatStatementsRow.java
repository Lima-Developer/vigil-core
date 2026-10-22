package com.vigil.adapters.postgres.pgstatstatements;

/** One raw row of the collection query. Every column is nullable at the source, hence the boxed types. */
record PgStatStatementsRow(Long dbid, Long userid, Long queryId, Boolean topLevel, Long calls,
                           Double totalExecTimeMillis, Long rows, Long sharedBlksHit, Long sharedBlksRead,
                           Long tempBlksRead, Long tempBlksWritten) {
}
