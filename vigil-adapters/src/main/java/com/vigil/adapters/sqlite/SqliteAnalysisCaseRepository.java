package com.vigil.adapters.sqlite;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.core.domain.AnalysisCase;
import com.vigil.core.domain.CaseEvent;
import com.vigil.core.domain.Evidence;
import com.vigil.core.domain.interfaces.DomainEvent;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite adapter for {@link AnalysisCaseRepository}. Each operation uses its own connection and one transaction.
 * <p>
 * Writes run on a {@code BEGIN IMMEDIATE} connection, so the version read and the version-guarded {@code UPDATE}
 * cannot interleave with another writer. The in-memory version and pending domain events are confirmed only after
 * {@code commit} succeeded. The schema and the persisted representations are described in
 * {@code docs/architecture/analysis-case-persistence.md}.
 */
public final class SqliteAnalysisCaseRepository implements AnalysisCaseRepository {
    private final SqliteConnectionFactory connections;
    private final AnalysisCaseRowMapper caseMapper = new AnalysisCaseRowMapper();
    private final CaseEventRowMapper eventMapper = new CaseEventRowMapper();
    private final EvidenceRowMapper evidenceMapper = new EvidenceRowMapper();
    private final DomainEventRowMapper domainEventMapper = new DomainEventRowMapper();

    public SqliteAnalysisCaseRepository(SqliteConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "Connection factory must not be null.");
    }

    @Override
    public void save(AnalysisCase analysisCase) throws ConcurrentCaseUpdate {
        Objects.requireNonNull(analysisCase, "Analysis case must not be null.");
        var outcome = execute(true, connection -> saveIn(connection, analysisCase));
        analysisCase.confirmPersisted(outcome.version(), outcome.eventIds());
    }

    @Override
    public void saveDiscovered(AnalysisCase analysisCase, String idempotencyKey) throws ConcurrentCaseUpdate {
        Objects.requireNonNull(analysisCase, "Analysis case must not be null.");
        requireKey(idempotencyKey);
        var outcome = execute(true, connection -> saveDiscoveredIn(connection, analysisCase, idempotencyKey));
        analysisCase.confirmPersisted(outcome.version(), outcome.eventIds());
    }

    @Override
    public Optional<AnalysisCase> findByIdempotencyKey(String idempotencyKey) {
        requireKey(idempotencyKey);
        return execute(false, connection -> load(connection, AnalysisCaseRowMapper.SELECT_BY_KEY, idempotencyKey));
    }

    @Override
    public AnalysisCase getById(UUID caseId) throws CaseNotFound {
        Objects.requireNonNull(caseId, "Case id must not be null.");
        return execute(false, connection -> load(connection, AnalysisCaseRowMapper.SELECT_BY_ID, caseId.toString()))
                .orElseThrow(() -> new CaseNotFound(caseId));
    }

    private Outcome saveIn(Connection connection, AnalysisCase analysisCase) throws SQLException {
        var stored = load(connection, AnalysisCaseRowMapper.SELECT_BY_ID, analysisCase.caseId().toString());
        var newEvents = unstoredEvents(connection, analysisCase);

        if (stored.isEmpty()) {
            if (analysisCase.version() != 0) {
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
            insertCase(connection, analysisCase, null);
            insertChildren(connection, analysisCase, 0, 0, newEvents);
            return outcome(1, analysisCase);
        }

        var current = stored.get();
        if (newEvents.isEmpty() && current.version() >= analysisCase.version() && sameState(current, analysisCase)) {
            return outcome(current.version(), analysisCase);
        }
        if (current.version() != analysisCase.version() || !extendsStored(current, analysisCase)) {
            throw new ConcurrentCaseUpdate(analysisCase.caseId());
        }

        try (var update = connection.prepareStatement(AnalysisCaseRowMapper.UPDATE)) {
            caseMapper.bindUpdate(update, analysisCase);
            if (update.executeUpdate() != 1) {
                throw new ConcurrentCaseUpdate(analysisCase.caseId());
            }
        }
        insertChildren(connection, analysisCase, current.events().size(), current.evidences().size(), newEvents);
        return outcome(analysisCase.version() + 1, analysisCase);
    }

    private Outcome saveDiscoveredIn(Connection connection, AnalysisCase analysisCase, String key) throws SQLException {
        var newEvents = unstoredEvents(connection, analysisCase);

        if (analysisCase.version() == 0 && insertDiscovered(connection, analysisCase, key)) {
            insertChildren(connection, analysisCase, 0, 0, newEvents);
            return outcome(1, analysisCase);
        }

        var owner = load(connection, AnalysisCaseRowMapper.SELECT_BY_KEY, key);
        if (owner.isPresent() && newEvents.isEmpty() && owner.get().caseId().equals(analysisCase.caseId())
                && owner.get().version() >= analysisCase.version() && sameState(owner.get(), analysisCase)) {
            return outcome(owner.get().version(), analysisCase);
        }
        throw new ConcurrentCaseUpdate(analysisCase.caseId());
    }

    /** Plain INSERT; returns false only when the key or the case id is already taken. */
    private boolean insertDiscovered(Connection connection, AnalysisCase analysisCase, String key) throws SQLException {
        try {
            insertCase(connection, analysisCase, key);
            return true;
        } catch (SQLException failure) {
            if (isUniqueViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    private static boolean isUniqueViolation(SQLException failure) {
        return failure instanceof SQLiteException sqlite
                && (sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                || sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY);
    }

    private void insertCase(Connection connection, AnalysisCase analysisCase, String key) throws SQLException {
        try (var insert = connection.prepareStatement(AnalysisCaseRowMapper.INSERT)) {
            caseMapper.bindInsert(insert, analysisCase, 1, key);
            insert.executeUpdate();
        }
    }

    /** Appends only the history suffix and evidences after the stored prefix, then the new domain events. */
    private void insertChildren(Connection connection, AnalysisCase analysisCase, int storedEvents,
                                int storedEvidences, List<DomainEvent> newEvents) throws SQLException {
        var caseId = analysisCase.caseId();
        var events = analysisCase.events();
        try (var insert = connection.prepareStatement(CaseEventRowMapper.INSERT)) {
            for (int index = storedEvents; index < events.size(); index++) {
                eventMapper.bind(insert, caseId, index + 1L, events.get(index));
                insert.executeUpdate();
            }
        }
        var evidences = analysisCase.evidences();
        try (var insert = connection.prepareStatement(EvidenceRowMapper.INSERT)) {
            for (int index = storedEvidences; index < evidences.size(); index++) {
                evidenceMapper.bind(insert, evidences.get(index), index + 1L);
                insert.executeUpdate();
            }
        }
        if (newEvents.isEmpty()) {
            return;
        }
        long sequence;
        try (var next = connection.prepareStatement(DomainEventRowMapper.NEXT_SEQUENCE)) {
            next.setString(1, caseId.toString());
            try (var result = next.executeQuery()) {
                result.next();
                sequence = result.getLong(1);
            }
        }
        try (var insert = connection.prepareStatement(DomainEventRowMapper.INSERT)) {
            for (var event : newEvents) {
                domainEventMapper.bind(insert, event, sequence++);
                insert.executeUpdate();
            }
        }
    }

    /** Pending events not stored yet; an id already stored with different content is a conflict. */
    private List<DomainEvent> unstoredEvents(Connection connection, AnalysisCase analysisCase) throws SQLException {
        var unstored = new ArrayList<DomainEvent>();
        try (var select = connection.prepareStatement(DomainEventRowMapper.SELECT_BY_ID)) {
            for (var event : analysisCase.pendingEvents()) {
                select.setString(1, event.eventId().toString());
                try (var result = select.executeQuery()) {
                    if (!result.next()) {
                        unstored.add(event);
                    } else if (!domainEventMapper.read(result).equals(event)) {
                        throw new ConcurrentCaseUpdate(analysisCase.caseId());
                    }
                }
            }
        }
        return unstored;
    }

    private Optional<AnalysisCase> load(Connection connection, String selectCase, String parameter)
            throws SQLException {
        AnalysisCaseRowMapper.Row row;
        try (var select = connection.prepareStatement(selectCase)) {
            select.setString(1, parameter);
            try (var result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                row = caseMapper.read(result);
            }
        }

        var events = new ArrayList<CaseEvent>();
        try (var select = connection.prepareStatement(CaseEventRowMapper.SELECT)) {
            select.setString(1, row.caseId().toString());
            try (var result = select.executeQuery()) {
                while (result.next()) {
                    requireSequence(eventMapper.sequence(result), events.size());
                    events.add(eventMapper.read(result));
                }
            }
        }
        var evidences = new ArrayList<Evidence>();
        try (var select = connection.prepareStatement(EvidenceRowMapper.SELECT)) {
            select.setString(1, row.caseId().toString());
            try (var result = select.executeQuery()) {
                while (result.next()) {
                    requireSequence(evidenceMapper.sequence(result), evidences.size());
                    evidences.add(evidenceMapper.read(result));
                }
            }
        }
        return Optional.of(caseMapper.toAggregate(row, events, evidences));
    }

    private static void requireSequence(long sequence, int alreadyRead) {
        if (sequence != alreadyRead + 1L) {
            throw new CorruptAnalysisCaseDataException("Persisted sequence is not contiguous.");
        }
    }

    private static boolean sameState(AnalysisCase stored, AnalysisCase candidate) {
        return stored.status() == candidate.status() && extendsStored(stored, candidate)
                && stored.events().equals(candidate.events()) && stored.evidences().equals(candidate.evidences());
    }

    /** Same identity, and the stored history and evidences are a prefix of the candidate's. */
    private static boolean extendsStored(AnalysisCase stored, AnalysisCase candidate) {
        return stored.createdAt().equals(candidate.createdAt())
                && stored.queryFingerprint().equals(candidate.queryFingerprint())
                && isPrefix(stored.events(), candidate.events())
                && isPrefix(stored.evidences(), candidate.evidences());
    }

    private static boolean isPrefix(List<?> prefix, List<?> list) {
        return prefix.size() <= list.size() && list.subList(0, prefix.size()).equals(prefix);
    }

    private static Outcome outcome(long version, AnalysisCase analysisCase) {
        return new Outcome(version, analysisCase.pendingEvents().stream().map(DomainEvent::eventId).toList());
    }

    private static void requireKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null.");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank.");
        }
    }

    private <T> T execute(boolean write, Work<T> work) {
        try (var connection = write ? connections.openForWrite() : connections.open()) {
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new SqlitePersistenceException("SQLite operation on analysis cases failed.", failure);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private record Outcome(long version, List<UUID> eventIds) {
    }
}
