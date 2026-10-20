package com.vigil.application.port.out.interfaces;

import java.util.List;
import java.util.UUID;

/**
 * Read-only port over the domain events persisted for a case (the audit trail).
 * <p>
 * It exposes only event types and metadata, in the order they were recorded. It never writes, never returns
 * payloads or SQL, and an unknown case simply has no events.
 */
public interface AnalysisCaseAuditReader {

    /**
     * Returns the persisted events of {@code caseId} in recording order, identified by type codes such as
     * {@code QUERY_TRIAGED}; empty when there are none.
     */
    List<AuditedEvent> eventsOf(UUID caseId);
}
