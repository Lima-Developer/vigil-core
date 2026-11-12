WITH totals AS (
 SELECT o.customer_id, count(*) AS calls, sum(o.total_cents) AS revenue
 FROM corpus.orders o JOIN corpus.customers c ON c.id=o.customer_id
 WHERE o.status='COMPLETED' AND c.segment='ENTERPRISE' GROUP BY o.customer_id
)
SELECT customer_id AS id, calls, revenue FROM totals;
