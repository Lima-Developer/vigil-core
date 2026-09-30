-- Test-only failing evolution: DDL and DML succeed before a CHECK constraint violation.
CREATE TABLE fixture_note (
    note_id INTEGER PRIMARY KEY,
    body    TEXT    NOT NULL
);
-- vigil:statement-end
INSERT INTO fixture_note (note_id, body) VALUES (1, 'partial');
-- vigil:statement-end
UPDATE analysis_case SET version = version + 1;
-- vigil:statement-end
INSERT INTO analysis_case (case_id, created_at, status)
VALUES ('33333333-3333-3333-3333-333333333333', '2026-09-30T12:00:00.000000000Z', 'CLOSED');
