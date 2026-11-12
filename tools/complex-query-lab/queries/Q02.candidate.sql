WITH totals AS (
 SELECT customer_id, sum(total_cents) AS revenue, max(placed_at) AS latest
 FROM corpus.orders WHERE status='COMPLETED' GROUP BY customer_id
)
SELECT c.id, t.revenue, t.latest FROM corpus.customers c
LEFT JOIN totals t ON t.customer_id=c.id WHERE c.region='NORTH';
