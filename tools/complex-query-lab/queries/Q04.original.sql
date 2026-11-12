SELECT c.region, count(DISTINCT o.id) AS orders,
 sum(i.quantity::bigint*i.unit_cents) AS revenue
FROM corpus.customers c JOIN corpus.orders o ON o.customer_id=c.id
JOIN corpus.order_items i ON i.order_id=o.id
WHERE o.status='COMPLETED' GROUP BY c.region;
