\set ON_ERROR_STOP on
SET timezone = 'UTC';
CREATE SCHEMA corpus;
SET search_path = corpus, public;
CREATE TABLE customers(id integer PRIMARY KEY, region text NOT NULL, segment text NOT NULL);
CREATE TABLE categories(id integer PRIMARY KEY, parent_id integer REFERENCES categories, name text NOT NULL);
CREATE TABLE products(id integer PRIMARY KEY, category_id integer NOT NULL REFERENCES categories, price_cents integer NOT NULL);
CREATE TABLE orders(id integer PRIMARY KEY, customer_id integer NOT NULL REFERENCES customers, status text NOT NULL, placed_at timestamptz NOT NULL, total_cents integer);
CREATE TABLE order_items(id integer PRIMARY KEY, order_id integer NOT NULL REFERENCES orders, product_id integer NOT NULL REFERENCES products, quantity integer NOT NULL, unit_cents integer NOT NULL);
CREATE TABLE payments(id integer PRIMARY KEY, order_id integer NOT NULL REFERENCES orders, status text NOT NULL);
CREATE TABLE events(id integer PRIMARY KEY, customer_id integer NOT NULL REFERENCES customers, occurred_at timestamptz NOT NULL, payload jsonb NOT NULL);

-- Fixed arithmetic generator, no random() or wall clock. The final 10% of customers have no orders.
INSERT INTO customers SELECT i, (ARRAY['NORTH','SOUTH','EAST','WEST'])[1+(i+:seed)%4],
  (ARRAY['RETAIL','ENTERPRISE','STARTUP'])[1+(i+:seed)%3]
FROM generate_series(1, 2000*:scale) i;
INSERT INTO categories SELECT i, CASE WHEN i=1 THEN NULL ELSE (i-2)/3+1 END, 'category-'||i
FROM generate_series(1,41) i;
INSERT INTO products SELECT i, 1+(i+:seed)%40, 100+(i*37+:seed)%10000 FROM generate_series(1,500) i;
INSERT INTO products VALUES (501,40,1000000000),(502,40,1000000000);
-- Half the orders belong to customer 1: intentionally skewed joins. Repeated timestamps and totals create ties.
INSERT INTO orders SELECT i, CASE WHEN i%2=0 THEN 1 ELSE 1+(i*17+:seed)%(1800*:scale) END,
  CASE WHEN i%101=0 THEN 'PENDING' WHEN i%7=0 THEN 'CANCELLED' ELSE 'COMPLETED' END,
  timestamptz '2024-01-01 00:00:00+00'+((i*13+:seed)%366)*interval '1 day',
  CASE WHEN i%97=0 THEN NULL ELSE 100+(i*31+:seed)%100000 END
FROM generate_series(1,20000*:scale) i;
INSERT INTO order_items SELECT (o.id-1)*3+j, o.id, 1+(o.id*11+j+:seed)%500,
  1+(o.id+j)%4, p.price_cents
FROM orders o CROSS JOIN generate_series(1,3) j
JOIN products p ON p.id=1+(o.id*11+j+:seed)%500;
-- Guaranteed tie at the top of category 40, to exercise DENSE_RANK semantics.
INSERT INTO order_items VALUES (60000*:scale+1,1,501,1,1000000000),(60000*:scale+2,1,502,1,1000000000);
INSERT INTO payments SELECT id,id,CASE WHEN id%5=0 THEN 'FAILED' ELSE 'PAID' END FROM orders WHERE id%3<>0;
-- Multiple PAID rows per order are intentional: anti-join and aggregation must not multiply results.
INSERT INTO payments SELECT id+20000*:scale,id,'PAID' FROM orders WHERE id%10=0;
INSERT INTO events SELECT i, 1+(i*7+:seed)%(2000*:scale),
  timestamptz '2024-01-01 00:00:00+00'+(i%90)*interval '1 day',
  jsonb_build_object('type',CASE WHEN i%3=0 THEN 'checkout' ELSE 'view' END,
    'context',jsonb_build_object('tier',CASE WHEN i%11=0 THEN 'premium' ELSE 'basic' END))
FROM generate_series(1,12000*:scale) i;
-- Missing JSON paths and JSON null must be excluded consistently.
INSERT INTO events VALUES (12000*:scale+1,1,'2024-06-01 00:00:00+00','{"type":"checkout"}'),
  (12000*:scale+2,1,'2024-06-01 00:00:00+00','{"type":"checkout","context":{"tier":null}}'),
  (12000*:scale+3,1,'2024-06-01 00:00:00+00','{"type":"checkout","context":{"tier":"premium"}}'),
  (12000*:scale+4,1,'2024-06-01 00:00:00+00','{"type":"checkout","context":{"tier":"premium"}}');
CREATE INDEX orders_customer_idx ON orders(customer_id);
CREATE INDEX orders_placed_idx ON orders(placed_at);
CREATE INDEX items_order_idx ON order_items(order_id);
CREATE INDEX payments_order_idx ON payments(order_id);
CREATE INDEX events_payload_idx ON events USING gin(payload jsonb_path_ops);
ANALYZE;
