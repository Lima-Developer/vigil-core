package com.vigil.application.telemetry;

/** Stable identity of one pg_stat_statements entry inside a server/version epoch. */
record QueryCounterKey(long dbid, long userid, long queryId, boolean topLevel) { }
