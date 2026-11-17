-- Analysis case structure and state transition history. See docs/architecture/sqlite-schema.md.
-- Statements are separated by the delimiter line below; each segment holds exactly one statement.
CREATE TABLE analysis_case (
    case_id                       TEXT    PRIMARY KEY NOT NULL
        CHECK (typeof(case_id) = 'text' AND length(trim(case_id, char(32, 9, 10, 13))) > 0),
    created_at                    TEXT    NOT NULL
        CHECK (typeof(created_at) = 'text'
            AND created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    status                        TEXT    NOT NULL
        CHECK (status IN ('DISCOVERED', 'TRIAGED', 'EVIDENCE_COLLECTED', 'SANITIZED', 'DIAGNOSED', 'HYPOTHESIS_PROPOSED', 'VALIDATING', 'VALIDATED', 'INVALIDATED', 'INCONCLUSIVE', 'AWAITING_HUMAN', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'ARCHIVED')),
    version                       INTEGER NOT NULL DEFAULT 0
        CHECK (typeof(version) = 'integer' AND version >= 0),
    query_fingerprint             TEXT
        CHECK (query_fingerprint IS NULL
            OR (typeof(query_fingerprint) = 'text' AND length(trim(query_fingerprint, char(32, 9, 10, 13))) > 0)),
    fingerprint_algorithm_version TEXT
        CHECK (fingerprint_algorithm_version IS NULL
            OR (typeof(fingerprint_algorithm_version) = 'text'
                AND length(trim(fingerprint_algorithm_version, char(32, 9, 10, 13))) > 0)),
    discovery_idempotency_key     TEXT    UNIQUE
        CHECK (discovery_idempotency_key IS NULL
            OR (typeof(discovery_idempotency_key) = 'text'
                AND length(trim(discovery_idempotency_key, char(32, 9, 10, 13))) > 0)),
    CHECK ((query_fingerprint IS NULL) = (fingerprint_algorithm_version IS NULL))
);
-- vigil:statement-end
CREATE TABLE case_event (
    case_id     TEXT    NOT NULL
        REFERENCES analysis_case (case_id) ON DELETE RESTRICT,
    sequence    INTEGER NOT NULL
        CHECK (typeof(sequence) = 'integer' AND sequence > 0),
    from_status TEXT    NOT NULL
        CHECK (from_status IN ('DISCOVERED', 'TRIAGED', 'EVIDENCE_COLLECTED', 'SANITIZED', 'DIAGNOSED', 'HYPOTHESIS_PROPOSED', 'VALIDATING', 'VALIDATED', 'INVALIDATED', 'INCONCLUSIVE', 'AWAITING_HUMAN', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'ARCHIVED')),
    to_status   TEXT    NOT NULL
        CHECK (to_status IN ('DISCOVERED', 'TRIAGED', 'EVIDENCE_COLLECTED', 'SANITIZED', 'DIAGNOSED', 'HYPOTHESIS_PROPOSED', 'VALIDATING', 'VALIDATED', 'INVALIDATED', 'INCONCLUSIVE', 'AWAITING_HUMAN', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'ARCHIVED')),
    actor       TEXT    NOT NULL
        CHECK (typeof(actor) = 'text' AND length(trim(actor, char(32, 9, 10, 13))) > 0),
    reason      TEXT    NOT NULL
        CHECK (typeof(reason) = 'text' AND length(trim(reason, char(32, 9, 10, 13))) > 0),
    occurred_at TEXT    NOT NULL
        CHECK (typeof(occurred_at) = 'text'
            AND occurred_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    PRIMARY KEY (case_id, sequence)
);
-- vigil:statement-end
CREATE INDEX idx_analysis_case_status_created_at ON analysis_case (status, created_at);
-- vigil:statement-end
CREATE INDEX idx_analysis_case_created_at ON analysis_case (created_at);
-- vigil:statement-end
CREATE INDEX idx_case_event_case_id_occurred_at ON case_event (case_id, occurred_at);
-- vigil:statement-end
CREATE INDEX idx_case_event_occurred_at ON case_event (occurred_at);
