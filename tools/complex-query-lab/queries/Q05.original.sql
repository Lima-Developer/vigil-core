WITH sales AS (
 SELECT p.category_id, p.id AS product_id, sum(i.quantity::bigint*i.unit_cents) AS revenue
 FROM corpus.products p JOIN corpus.order_items i ON i.product_id=p.id
 JOIN corpus.orders o ON o.id=i.order_id WHERE o.status='COMPLETED'
 GROUP BY p.category_id,p.id
), ranked AS (
 SELECT *, dense_rank() OVER (PARTITION BY category_id ORDER BY revenue DESC) AS position FROM sales
)
SELECT category_id, product_id, revenue, position FROM ranked WHERE position<=3;
