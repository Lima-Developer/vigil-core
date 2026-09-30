-- Test-only invalid script: not UTF-8.
CREATE TABLE fixture_note (body TEXT DEFAULT 'ÿ');
