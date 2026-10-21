package com.vigil.adapters.postgres.pgstatstatements;

import com.vigil.application.port.out.interfaces.PostgresPreflightFacts;
import com.vigil.application.preflight.CapabilityCode;
import java.util.Optional;

/** Capabilities the collection needs, checked from the preflight facts before the statistics view is touched. */
final class PgStatStatementsCapabilities {
    static final int MINIMUM_VERSION = 140000;

    private PgStatStatementsCapabilities() {
    }

    static Optional<CapabilityCode> firstMissing(PostgresPreflightFacts facts) {
        if (facts.serverVersionNum() < MINIMUM_VERSION) return Optional.of(CapabilityCode.SERVER_VERSION);
        if (!facts.extensionInstalled()) return Optional.of(CapabilityCode.PG_STAT_STATEMENTS_INSTALLED);
        if (!facts.preloaded()) return Optional.of(CapabilityCode.PG_STAT_STATEMENTS_PRELOADED);
        String queryId = facts.computeQueryId();
        if (!"on".equalsIgnoreCase(queryId) && !"auto".equalsIgnoreCase(queryId)) {
            return Optional.of(CapabilityCode.COMPUTE_QUERY_ID);
        }
        return Optional.empty();
    }
}
