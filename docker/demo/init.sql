CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE TABLE lab_orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint NOT NULL,
    status text NOT NULL,
    total numeric(12,2) NOT NULL,
    created_at timestamptz NOT NULL
);

INSERT INTO lab_orders(customer_id, status, total, created_at)
SELECT (n % 5000) + 1,
       CASE WHEN n % 7 = 0 THEN 'OPEN' ELSE 'CLOSED' END,
       ((n % 10000) + 100)::numeric / 100,
       timestamptz '2026-01-01 00:00:00+00' + (n || ' seconds')::interval
FROM generate_series(1, 100000) AS n;

ANALYZE lab_orders;

CREATE ROLE vigil_reader LOGIN PASSWORD 'synthetic-demo-reader';
GRANT CONNECT ON DATABASE vigil_lab TO vigil_reader;
GRANT USAGE ON SCHEMA public TO vigil_reader;
GRANT SELECT ON lab_orders TO vigil_reader;
GRANT pg_monitor TO vigil_reader;
ALTER ROLE vigil_reader SET default_transaction_read_only = on;
