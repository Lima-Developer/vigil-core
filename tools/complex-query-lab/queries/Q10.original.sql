WITH ranked AS (
 SELECT e.id,e.customer_id,e.occurred_at,
 row_number() OVER (PARTITION BY e.customer_id ORDER BY e.occurred_at DESC,e.id DESC) AS rn
 FROM corpus.events e WHERE e.payload->>'type'='checkout'
 AND e.payload#>>'{context,tier}'='premium'
)
SELECT c.id AS customer_id,c.region,r.id AS event_id,r.occurred_at
FROM ranked r JOIN corpus.customers c ON c.id=r.customer_id WHERE r.rn=1;
