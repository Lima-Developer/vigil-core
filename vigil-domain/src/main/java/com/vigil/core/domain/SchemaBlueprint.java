package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.SchemaIncompleteness;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The relevant part of a target schema, collected at {@code collectedAt} from {@code source}. Every name is a
 * pseudonym valid for this collection only. A non-empty {@code incompleteness} means the blueprint is explicitly
 * incomplete and must not be read as "nothing exists".
 */
public record SchemaBlueprint(
        Instant collectedAt,
        EvidenceSource source,
        List<RelationBlueprint> relations,
        Set<SchemaIncompleteness> incompleteness
) {
    public SchemaBlueprint {
        Objects.requireNonNull(collectedAt, "Collected at must not be null.");
        Objects.requireNonNull(source, "Source must not be null.");
        relations = List.copyOf(Objects.requireNonNull(relations, "Relations must not be null."));
        incompleteness = Set.copyOf(Objects.requireNonNull(incompleteness, "Incompleteness must not be null."));

        if (relations.stream().map(RelationBlueprint::relation).distinct().count() != relations.size()) {
            throw new IllegalArgumentException("A relation must appear once per blueprint.");
        }
    }

    public boolean complete() {
        return incompleteness.isEmpty();
    }
}
