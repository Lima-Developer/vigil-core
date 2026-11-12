SELECT c.id AS customer_id, o.id AS order_id, o.total_cents FROM corpus.customers c
CROSS JOIN LATERAL (
 SELECT id,total_cents FROM corpus.orders WHERE customer_id=c.id AND status='COMPLETED'
 ORDER BY total_cents DESC NULLS LAST,id DESC LIMIT 3
) o WHERE c.segment='STARTUP';
