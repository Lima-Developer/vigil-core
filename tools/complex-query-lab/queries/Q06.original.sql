SELECT c.id, (
 WITH RECURSIVE descendants AS (
  SELECT id FROM corpus.categories WHERE id=c.id
  UNION ALL
  SELECT child.id FROM corpus.categories child JOIN descendants d ON child.parent_id=d.id
 )
 SELECT count(*) FROM corpus.products p JOIN descendants d ON d.id=p.category_id
) AS products FROM corpus.categories c;
