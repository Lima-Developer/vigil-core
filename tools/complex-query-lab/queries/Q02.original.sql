SELECT c.id,
 (SELECT sum(o.total_cents) FROM corpus.orders o WHERE o.customer_id=c.id AND o.status='COMPLETED') AS revenue,
 (SELECT max(o.placed_at) FROM corpus.orders o WHERE o.customer_id=c.id AND o.status='COMPLETED') AS latest
FROM corpus.customers c WHERE c.region='NORTH';
