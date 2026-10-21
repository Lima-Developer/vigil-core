-- Isolated fixture for the schema catalog collector (VIG-035). Laboratory only; needs a superuser because of the
-- foreign data wrapper. Names and literals are deliberately sensitive-looking: the collector must never surface them.
CREATE SCHEMA vig035_fixture;

CREATE TYPE vig035_fixture.account_tier AS ENUM ('gold', 'silver');

CREATE TABLE vig035_fixture.accounts_secret (
    id         bigint PRIMARY KEY,
    owner_cpf  text NOT NULL,
    email      varchar(120),
    balance    numeric(12, 2),
    tier       vig035_fixture.account_tier,
    note       text,
    CONSTRAINT accounts_secret_email_key UNIQUE (email),
    CONSTRAINT accounts_secret_balance_check CHECK (balance >= 1234567 OR owner_cpf <> '000.111.222-33')
);
INSERT INTO vig035_fixture.accounts_secret (id, owner_cpf, email, balance, tier, note) VALUES
    (1, '000.111.222-33', 'ana@example.test', 1234568.00, 'gold', 'vip customer'),
    (2, '444.555.666-77', 'bia@example.test', 10.00, 'silver', NULL),
    (3, '888.999.000-11', NULL, NULL, NULL, NULL);
-- Partial index: the predicate literal must not be exposed, only the fact that the index is partial.
CREATE INDEX accounts_secret_vip_idx ON vig035_fixture.accounts_secret (owner_cpf) WHERE balance > 1234567;
CREATE INDEX accounts_secret_email_lower_idx ON vig035_fixture.accounts_secret ((lower(email)), id);
ANALYZE vig035_fixture.accounts_secret;

CREATE MATERIALIZED VIEW vig035_fixture.account_totals_secret AS
    SELECT owner_cpf, sum(balance) AS total_balance FROM vig035_fixture.accounts_secret GROUP BY owner_cpf;
CREATE UNIQUE INDEX account_totals_secret_cpf_idx ON vig035_fixture.account_totals_secret (owner_cpf);
ANALYZE vig035_fixture.account_totals_secret;

CREATE TABLE vig035_fixture.events_secret (
    id          bigint NOT NULL,
    tenant_id   integer NOT NULL,
    happened_at timestamptz NOT NULL,
    payload     jsonb
) PARTITION BY RANGE (happened_at);
CREATE TABLE vig035_fixture.events_secret_2026 PARTITION OF vig035_fixture.events_secret
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
-- Created on the parent, PostgreSQL creates an equivalent index on every partition (a distinct catalog entry each).
CREATE INDEX events_secret_tenant_idx ON vig035_fixture.events_secret (tenant_id);

-- A foreign table needs no extension and is never read: the wrapper has no handler.
CREATE FOREIGN DATA WRAPPER vig035_fdw;
CREATE SERVER vig035_server FOREIGN DATA WRAPPER vig035_fdw;
CREATE FOREIGN TABLE vig035_fixture.remote_ledger_secret (
    entry_id integer NOT NULL,
    amount   numeric(10, 2)
) SERVER vig035_server;

-- Never analyzed and never auto-analyzed: reltuples stays unknown (-1).
CREATE TABLE vig035_fixture.fresh_secret (id integer) WITH (autovacuum_enabled = false);

CREATE VIEW vig035_fixture.plain_view_secret AS SELECT id FROM vig035_fixture.fresh_secret;
