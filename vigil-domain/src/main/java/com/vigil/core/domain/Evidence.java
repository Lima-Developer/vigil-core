package com.vigil.core.domain;

import com.vigil.core.domain.enums.EvidenceConfidence;
import com.vigil.core.domain.enums.EvidenceSource;
import com.vigil.core.domain.enums.EvidenceType;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Evidence(
        UUID id,
        UUID caseId,
        EvidenceType type,
        EvidenceSource source,
        Instant capturedAt,
        String collectorVersion,
        EvidenceConfidence confidence,
        EvidencePayload safePayload,
        Optional<ProtectedEvidenceReference> protectedReference
) {
    public Evidence {
        Objects.requireNonNull(id, "Evidence id must not be null.");
        Objects.requireNonNull(caseId, "Case id must not be null.");
        Objects.requireNonNull(type, "Evidence type must not be null.");
        Objects.requireNonNull(source, "Evidence source must not be null.");
        Objects.requireNonNull(capturedAt, "Captured at must not be null.");
        Objects.requireNonNull(confidence, "Evidence confidence must not be null.");
        Objects.requireNonNull(safePayload, "Safe payload must not be null.");
        Objects.requireNonNull(protectedReference, "Protected reference must not be null.");

        if (collectorVersion == null || collectorVersion.isBlank()) {
            throw new IllegalArgumentException("Collector version must not be blank.");
        }

        if (type != safePayload.type()) {
            throw new IllegalArgumentException("Evidence type must match the safe payload type.");
        }
    }

    public void requireBelongsTo(UUID expectedCaseId) {
        if (!caseId.equals(Objects.requireNonNull(expectedCaseId, "Expected case id must not be null."))) {
            throw new IllegalArgumentException("Evidence belongs to a different analysis case.");
        }
    }
}
