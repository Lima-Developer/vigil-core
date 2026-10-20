-- Test-only failing first migration: DDL and DML succeed before referencing a missing table.
CREATE TABLE fixture_note (
    note_id INTEGER PRIMARY KEY,
    body    TEXT    NOT NULL
);
-- vigil:statement-end
INSERT INTO fixture_note (note_id, body) VALUES (1, 'partial');
-- vigil:statement-end
INSERT INTO missing_table (id) VALUES (1);
