WITH RECURSIVE tree AS (
 SELECT id AS ancestor, id AS descendant FROM corpus.categories
 UNION ALL
 SELECT t.ancestor, c.id FROM tree t JOIN corpus.categories c ON c.parent_id=t.descendant
)
SELECT t.ancestor AS id, count(p.id) AS products FROM tree t
LEFT JOIN corpus.products p ON p.category_id=t.descendant GROUP BY t.ancestor;
