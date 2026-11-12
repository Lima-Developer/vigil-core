WITH items AS (
 SELECT order_id, sum(quantity::bigint*unit_cents) AS revenue
 FROM corpus.order_items GROUP BY order_id
)
SELECT c.region, count(*) AS orders, sum(i.revenue) AS revenue
FROM corpus.customers c JOIN corpus.orders o ON o.customer_id=c.id
JOIN items i ON i.order_id=o.id
WHERE o.status='COMPLETED' GROUP BY c.region;
