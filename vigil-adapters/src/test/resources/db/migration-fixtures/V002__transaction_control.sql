-- Test-only invalid script: transaction control belongs to the runner.
CREATE TABLE fixture_note (note_id INTEGER PRIMARY KEY);
-- vigil:statement-end
-- a leading comment must not hide the transaction keyword
  commit;
