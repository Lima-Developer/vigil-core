SELECT c.region, count(*) AS orders, sum(o.total_cents) AS total
FROM corpus.orders o JOIN corpus.customers c ON c.id=o.customer_id
WHERE o.total_cents>50000 AND NOT EXISTS (
 SELECT 1 FROM corpus.payments p WHERE p.order_id=o.id AND p.status='PAID'
) GROUP BY c.region;
