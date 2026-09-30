-- Migration history. SqlMigrationRunner runs this script in the same transaction as the first functional
-- migration and never records it in the history. See docs/architecture/sqlite-schema.md.
CREATE TABLE schema_version (
    version      INTEGER PRIMARY KEY
        CHECK (version > 0),
    description  TEXT    NOT NULL
        CHECK (typeof(description) = 'text' AND length(trim(description, char(32, 9, 10, 13))) > 0),
    checksum     TEXT    NOT NULL
        CHECK (typeof(checksum) = 'text' AND length(checksum) = 64 AND checksum NOT GLOB '*[^0-9a-f]*'),
    installed_at TEXT    NOT NULL
        CHECK (typeof(installed_at) = 'text'
            AND installed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z')
);
