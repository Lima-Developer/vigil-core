SELECT c.region, count(*) AS orders, sum(o.total_cents) AS total
FROM corpus.orders o JOIN corpus.customers c ON c.id=o.customer_id
LEFT JOIN corpus.payments p ON p.order_id=o.id AND p.status='PAID'
WHERE o.total_cents>50000 AND p.id IS NULL GROUP BY c.region;
