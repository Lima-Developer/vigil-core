-- Test-only evolution: creates a table and a row whose literal contains semicolons.
CREATE TABLE fixture_note (
    note_id INTEGER PRIMARY KEY,
    body    TEXT    NOT NULL
);
-- vigil:statement-end
INSERT INTO fixture_note (note_id, body) VALUES (1, 'first; second;
-- vigil:statement-end is only a delimiter when alone on its line');
