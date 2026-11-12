WITH totals AS MATERIALIZED (
 SELECT customer_id, count(*) AS calls, sum(total_cents) AS revenue
 FROM corpus.orders WHERE status='COMPLETED' GROUP BY customer_id
)
SELECT c.id, t.calls, t.revenue FROM corpus.customers c
JOIN totals t ON t.customer_id=c.id WHERE c.segment='ENTERPRISE';
