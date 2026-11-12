SELECT c.region, count(*) AS orders, count(DISTINCT c.id) AS customers, sum(o.total_cents) AS revenue
FROM corpus.customers c JOIN corpus.orders o ON o.customer_id=c.id
WHERE o.placed_at>=timestamptz '2024-06-01 00:00:00+00'
 AND o.placed_at<timestamptz '2024-07-01 00:00:00+00'
 AND o.status='COMPLETED' GROUP BY c.region;
