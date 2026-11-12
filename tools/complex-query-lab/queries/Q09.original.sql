SELECT c.region, count(*) AS orders, count(DISTINCT c.id) AS customers, sum(o.total_cents) AS revenue
FROM corpus.customers c JOIN (
 SELECT * FROM corpus.orders WHERE date_trunc('month',placed_at)=timestamptz '2024-06-01 00:00:00+00'
 AND status='COMPLETED'
) o ON o.customer_id=c.id GROUP BY c.region;
