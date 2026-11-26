package com.vigil.application.casework;

import com.vigil.application.port.out.interfaces.ImpactTelemetrySnapshot;
import com.vigil.application.telemetry.KeyDelta;
import com.vigil.application.telemetry.PerKeyDeltaResult;
import com.vigil.application.telemetry.QueryTelemetryDeltaCalculator;
import com.vigil.core.domain.FamilyImpact;
import com.vigil.core.domain.ImpactSelectionPolicy;
import com.vigil.core.domain.ImpactSelectionResult;
import com.vigil.core.domain.QueryFingerprint;
import com.vigil.core.domain.SourceQueryId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Selects candidate families by absolute impact from the two most recent impact snapshots. It reuses the existing
 * per-key delta calculation and the deterministic {@link ImpactSelectionPolicy}; it groups keys by fingerprint
 * (the family, RF11), drops keys whose SQL could not be fingerprinted into an unclassified counter (fail-closed),
 * and never sees raw SQL. Warming propagates as an empty selection with the WARMING outcome.
 */
public final class SelectCandidatesByImpactUseCase {
    private final QueryTelemetryDeltaCalculator calculator;
    private final ImpactSelectionPolicy policy;

    public SelectCandidatesByImpactUseCase(QueryTelemetryDeltaCalculator calculator, ImpactSelectionPolicy policy) {
        this.calculator = Objects.requireNonNull(calculator, "Calculator must not be null.");
        this.policy = Objects.requireNonNull(policy, "Policy must not be null.");
    }

    public ImpactSelectionResult execute(ImpactTelemetrySnapshot previous, ImpactTelemetrySnapshot current) {
        Objects.requireNonNull(previous, "Previous snapshot must not be null.");
        Objects.requireNonNull(current, "Current snapshot must not be null.");

        PerKeyDeltaResult perKey = calculator.perKeyDeltas(previous.toCollection(), current.toCollection());
        if (perKey.isWarming()) {
            return ImpactSelectionResult.warming(0);
        }

        var fingerprintByKey = new LinkedHashMap<KeyId, Optional<QueryFingerprint>>();
        for (var entry : current.entries()) {
            var counters = entry.counters();
            fingerprintByKey.put(new KeyId(counters.dbid(), counters.userid(), counters.queryId(),
                    counters.topLevel()), entry.fingerprint());
        }

        var families = new LinkedHashMap<QueryFingerprint, Accumulator>();
        var workspaceTotal = BigDecimal.ZERO;
        int unclassified = 0;
        for (KeyDelta delta : perKey.deltas()) {
            workspaceTotal = workspaceTotal.add(delta.executionMillis());
            var fingerprint = fingerprintByKey.getOrDefault(
                    new KeyId(delta.dbid(), delta.userid(), delta.queryId(), delta.topLevel()), Optional.empty());
            if (fingerprint.isEmpty()) {
                unclassified++;
                continue;
            }
            families.computeIfAbsent(fingerprint.get(), key -> new Accumulator()).add(delta);
        }

        var familyImpacts = new ArrayList<FamilyImpact>();
        for (var entry : families.entrySet()) {
            familyImpacts.add(entry.getValue().toFamilyImpact(entry.getKey()));
        }
        return policy.select(familyImpacts, workspaceTotal, unclassified);
    }

    private record KeyId(long dbid, long userid, long queryId, boolean topLevel) { }

    /** Sums the deltas of every native key that shares one fingerprint and collects their query ids, sorted for determinism. */
    private static final class Accumulator {
        private long calls;
        private BigDecimal executionMillis = BigDecimal.ZERO;
        private long rows;
        private long sharedHits;
        private long sharedReads;
        private final TreeSet<String> sourceQueryIds = new TreeSet<>();

        private void add(KeyDelta delta) {
            calls += delta.calls();
            executionMillis = executionMillis.add(delta.executionMillis());
            rows += delta.rows();
            sharedHits += delta.sharedHits();
            sharedReads += delta.sharedReads();
            sourceQueryIds.add(Long.toString(delta.queryId()));
        }

        private FamilyImpact toFamilyImpact(QueryFingerprint fingerprint) {
            List<SourceQueryId> sources = sourceQueryIds.stream().map(SourceQueryId::new).toList();
            return new FamilyImpact(fingerprint, calls, executionMillis, rows, sharedHits, sharedReads, sources);
        }
    }
}
