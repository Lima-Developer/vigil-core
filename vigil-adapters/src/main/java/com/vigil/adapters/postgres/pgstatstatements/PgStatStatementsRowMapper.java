package com.vigil.adapters.postgres.pgstatstatements;

import com.vigil.application.port.out.interfaces.CumulativeQueryCounters;
import java.math.BigDecimal;
import java.util.Optional;

/** Turns a raw row into counters, or into nothing when the row is unusable. */
final class PgStatStatementsRowMapper {
    private PgStatStatementsRowMapper() {
    }

    static Optional<CumulativeQueryCounters> map(PgStatStatementsRow row) {
        if (row.dbid() == null || row.userid() == null || row.queryId() == null || row.topLevel() == null
                || row.calls() == null || row.totalExecTimeMillis() == null || row.rows() == null
                || row.sharedBlksHit() == null || row.sharedBlksRead() == null || row.tempBlksRead() == null
                || row.tempBlksWritten() == null) {
            return Optional.empty();
        }
        double millis = row.totalExecTimeMillis();
        if (!Double.isFinite(millis) || millis < 0 || row.dbid() < 0 || row.userid() < 0 || row.calls() < 0
                || row.rows() < 0 || row.sharedBlksHit() < 0 || row.sharedBlksRead() < 0
                || row.tempBlksRead() < 0 || row.tempBlksWritten() < 0) {
            return Optional.empty();
        }
        return Optional.of(new CumulativeQueryCounters(row.dbid(), row.userid(), row.queryId(), row.topLevel(),
                row.calls(), new BigDecimal(Double.toString(millis)), row.rows(), row.sharedBlksHit(),
                row.sharedBlksRead(), row.tempBlksRead(), row.tempBlksWritten()));
    }
}
