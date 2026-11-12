WITH ranked AS (
 SELECT c.id AS customer_id, o.id AS order_id, o.total_cents,
 row_number() OVER (PARTITION BY c.id ORDER BY o.total_cents DESC NULLS LAST,o.id DESC) AS rn
 FROM corpus.customers c JOIN corpus.orders o ON o.customer_id=c.id
 WHERE c.segment='STARTUP' AND o.status='COMPLETED'
)
SELECT customer_id, order_id, total_cents FROM ranked WHERE rn<=3;
