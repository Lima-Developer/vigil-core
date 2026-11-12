SELECT c.id,
 count(o.id) FILTER (WHERE o.status='COMPLETED') AS completed,
 count(o.id) FILTER (WHERE o.status='PENDING') AS pending,
 sum(o.total_cents) FILTER (WHERE o.status='COMPLETED') AS revenue
FROM corpus.customers c LEFT JOIN corpus.orders o ON o.customer_id=c.id GROUP BY c.id;
