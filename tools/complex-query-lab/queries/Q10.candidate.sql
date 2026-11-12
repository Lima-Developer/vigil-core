WITH latest AS (
 SELECT DISTINCT ON (customer_id) id,customer_id,occurred_at
 FROM corpus.events WHERE payload @> '{"type":"checkout","context":{"tier":"premium"}}'::jsonb
 ORDER BY customer_id,occurred_at DESC,id DESC
)
SELECT c.id AS customer_id,c.region,r.id AS event_id,r.occurred_at
FROM latest r JOIN corpus.customers c ON c.id=r.customer_id;
