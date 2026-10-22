-- Evidence (safe payload only) and pending/published domain events. See docs/architecture/sqlite-schema.md.
-- Statements are separated by the delimiter line below; each segment holds exactly one statement.
CREATE TABLE evidence (
    evidence_id         TEXT    PRIMARY KEY NOT NULL
        CHECK (typeof(evidence_id) = 'text' AND length(trim(evidence_id, char(32, 9, 10, 13))) > 0),
    case_id             TEXT    NOT NULL
        REFERENCES analysis_case (case_id) ON DELETE RESTRICT,
    sequence            INTEGER NOT NULL
        CHECK (typeof(sequence) = 'integer' AND sequence > 0),
    type                TEXT    NOT NULL
        CHECK (type IN ('METRIC', 'PLAN_SUMMARY', 'RELATION_METADATA')),
    source              TEXT    NOT NULL
        CHECK (source IN ('PG_STAT_STATEMENTS', 'POSTGRESQL_EXPLAIN', 'POSTGRESQL_CATALOG')),
    captured_at         TEXT    NOT NULL
        CHECK (typeof(captured_at) = 'text'
            AND captured_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    collector_version   TEXT    NOT NULL
        CHECK (typeof(collector_version) = 'text' AND length(trim(collector_version, char(32, 9, 10, 13))) > 0),
    confidence          TEXT    NOT NULL
        CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    payload             TEXT    NOT NULL
        CHECK (typeof(payload) = 'text' AND length(trim(payload, char(32, 9, 10, 13))) > 0),
    protected_reference TEXT
        CHECK (protected_reference IS NULL
            OR (typeof(protected_reference) = 'text'
                AND length(trim(protected_reference, char(32, 9, 10, 13))) > 0)),
    UNIQUE (case_id, sequence)
);
-- vigil:statement-end
CREATE TABLE domain_event (
    event_id      TEXT    PRIMARY KEY NOT NULL
        CHECK (typeof(event_id) = 'text' AND length(trim(event_id, char(32, 9, 10, 13))) > 0),
    case_id       TEXT    NOT NULL
        REFERENCES analysis_case (case_id) ON DELETE RESTRICT,
    sequence      INTEGER NOT NULL
        CHECK (typeof(sequence) = 'integer' AND sequence > 0),
    event_type    TEXT    NOT NULL
        CHECK (event_type IN ('ANALYSIS_CASE_DISCOVERED', 'QUERY_TRIAGED', 'EVIDENCE_COLLECTED')),
    actor         TEXT    NOT NULL
        CHECK (typeof(actor) = 'text' AND length(trim(actor, char(32, 9, 10, 13))) > 0),
    occurred_at   TEXT    NOT NULL
        CHECK (typeof(occurred_at) = 'text'
            AND occurred_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    event_version TEXT    NOT NULL
        CHECK (typeof(event_version) = 'text' AND length(trim(event_version, char(32, 9, 10, 13))) > 0),
    evidence_id   TEXT
        REFERENCES evidence (evidence_id) ON DELETE RESTRICT,
    CHECK ((event_type = 'EVIDENCE_COLLECTED') = (evidence_id IS NOT NULL)),
    UNIQUE (case_id, sequence)
);
-- vigil:statement-end
CREATE INDEX idx_evidence_captured_at ON evidence (captured_at);
-- vigil:statement-end
CREATE INDEX idx_domain_event_occurred_at ON domain_event (occurred_at);
