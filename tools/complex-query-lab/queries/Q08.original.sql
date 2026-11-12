SELECT c.id,
 (SELECT count(*) FROM corpus.orders o WHERE o.customer_id=c.id AND o.status='COMPLETED') AS completed,
 (SELECT count(*) FROM corpus.orders o WHERE o.customer_id=c.id AND o.status='PENDING') AS pending,
 (SELECT sum(o.total_cents) FROM corpus.orders o WHERE o.customer_id=c.id AND o.status='COMPLETED') AS revenue
FROM corpus.customers c;
