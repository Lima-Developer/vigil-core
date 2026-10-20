-- Laboratory-only role for the Vigil collector. Never provision this against a customer target.
-- Run as a superuser with psql, passing the secret by variable, never as a literal:
--   psql -v ON_ERROR_STOP=1 -v vigil_password="$VIGIL_LAB_PASSWORD" -f vigil-lab-role.sql
-- Requires PostgreSQL 16+: the per-grant INHERIT option keeps the role NOINHERIT for everything else.

CREATE ROLE vigil_collector
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'vigil_password';

GRANT CONNECT ON DATABASE :"DBNAME" TO vigil_collector;
GRANT pg_monitor TO vigil_collector WITH INHERIT TRUE;

-- Intentionally no GRANT on any table or schema object: statistics views and catalogs are readable through
-- pg_monitor and PUBLIC defaults, and pg_stats stays empty for tables the role cannot SELECT.
