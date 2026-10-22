-- Laboratory-only role for the Vigil collector. Never provision this against a customer target.
-- Run as a superuser with psql, passing the secret by variable, never as a literal:
--   psql -v ON_ERROR_STOP=1 -v vigil_password="$VIGIL_LAB_PASSWORD" -f vigil-lab-role.sql
-- PostgreSQL 16+ keeps the role NOINHERIT through the per-grant INHERIT option; 14 and 15 lack it, so there the
-- role is switched to INHERIT after the single pg_monitor grant (it holds no other membership).

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
SELECT current_setting('server_version_num')::int >= 160000 AS pg16 \gset
\if :pg16
GRANT pg_monitor TO vigil_collector WITH INHERIT TRUE;
\else
GRANT pg_monitor TO vigil_collector;
ALTER ROLE vigil_collector INHERIT;
\endif

-- PostgreSQL 14 lets PUBLIC create objects in schema public (revoked by default only from 15): close it everywhere,
-- so the privilege barrier does not depend on default_transaction_read_only.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Intentionally no GRANT on any table or schema object: statistics views and catalogs are readable through
-- pg_monitor and PUBLIC defaults, and pg_stats stays empty for tables the role cannot SELECT.
