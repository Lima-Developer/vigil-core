# Acervo de queries complexas — Vigil, versão 1

Laboratório complementar ao VIG-030, exclusivamente sintético e independente do banco monitorado.
Contém **10 consultas originais e 10 alternativas candidatas**, descritas no [catálogo](catalog.json).
As candidatas foram escritas manualmente: **não são saídas de LLM**, e esta etapa não consome nem estima tokens de um provedor.

## Executar

Pré-requisitos: Python 3.9+ e Docker com daemon ativo. Não exige `psql` no host nem pacote Python externo.
Na raiz do repositório:

```sh
python3 tools/complex-query-lab/run.py --seed 42 --scale 1 --repetitions 7 --output /tmp/vigil-corpus-seed42
```

O diretório de saída deve ser novo: evidências anteriores nunca são sobrescritas. A imagem é fixada pelo
digest PostgreSQL 16.4 já documentado no laboratório do projeto. Caso não exista localmente, Docker pode
baixá-la do registry antes de iniciar o banco.

O runner cria um container com nome UUID, **sem rede externa, sem portas publicadas e sem montagem do host**.
Limites: 2 CPUs, 1 GiB de RAM e 512 MiB de tmpfs para dados. O usuário administrativo só faz a carga desse banco
descartável; todas as queries, equivalência e planos rodam em transações `READ ONLY`, com timeout de 20 s por
comando. O container criado pelo runner é removido ao terminar, inclusive em falha. Nenhum container existente é alterado.
Não há parâmetro de conexão com target real. Este runner aceita apenas o corpus versionado e **não é um executor
seguro de SQL arbitrário gerado por IA**; a integração futura precisa da política/parser/sandbox do Vigil.

## Dataset

Gerador aritmético determinístico em [seed.sql](seed.sql), sem `random()` nem relógio de execução.
Seed padrão 42; escala inteira de 1 a 10. Data base `2024-01-01T00:00:00Z`, timezone UTC.

| Tabela | Linhas em escala S |
|---|---:|
| customers | 2.000 × S |
| categories | 41 |
| products | 502 |
| orders | 20.000 × S |
| order_items | 60.000 × S + 2 |
| payments | 20.000 × S − floor(20.000 × S / 3) + 2.000 × S |
| events | 12.000 × S + 4 |

- 50% dos pedidos pertencem ao cliente 1; os últimos 10% dos clientes não têm pedidos.
- O gerador modular também cria correlações entre região, cliente e pedidos: algumas regiões ficam sem atividade.
  É um caso de skew deliberado, **não uma distribuição representativa de produção**.
- Pedidos possuem status seletivos, totais nulos e timestamps repetidos; não há garantia de que total do pedido
  corresponda à soma dos itens — Q04 mede o valor dos itens, as demais somas medem o total registrado no pedido.
- Há pagamentos PAID duplicados, categoria sem produtos, empate garantido no topo de Q05,
  JSON com chave ausente/null e dois eventos premium com mesmo timestamp para testar desempate.
- Índices iniciais, além das PKs: `orders(customer_id)`, `orders(placed_at)`, `order_items(order_id)`,
  `payments(order_id)` e GIN `events(payload jsonb_path_ops)`. Nenhum índice muda entre original e candidata.
- `ANALYZE` executado após carga. O relatório guarda cardinalidade e hash de **todas as linhas ordenadas por id**,
  hash consolidado do dataset, SQL, gerador, runner e catálogo. Hash de conteúdo é para reprodutibilidade, não autenticação.

## Cenários e critérios

| ID | Construção principal | Hipótese / papel |
|---|---|---|
| Q01 | CTE MATERIALIZED + agregação + join | Antecipar filtro; pode regredir |
| Q02 | Duas subqueries correlacionadas | Pré-agregar valores e última data |
| Q03 | NOT EXISTS e anti-join | Planos potencialmente equivalentes; preservar ausência de ganho |
| Q04 | Três tabelas, DISTINCT e agregação | Pré-agregar itens em cenário com skew |
| Q05 | CTEs + DENSE_RANK | **Controle negativo: candidata idêntica** |
| Q06 | CTE recursiva dentro de subquery | Fechamento da árvore e contagem por ancestral |
| Q07 | ROW_NUMBER versus LATERAL LIMIT | Top-3 por cliente, com desempate estável |
| Q08 | Subqueries e agregações condicionais | FILTER com tratamento correto de COUNT/SUM vazios |
| Q09 | Subquery + expressão temporal + DISTINCT | Intervalo semiaberto no índice; pressupõe UTC |
| Q10 | JSONB + janela + join | Containment/GIN e DISTINCT ON, schema JSON controlado |

Literais, filtros, limites e desempates são fixados nos arquivos SQL; a entrada variável do experimento é
seed/escala. O catálogo registra o resultado esperado em termos de negócio, hipótese e riscos por consulta.
Não se assume que CTEs, subqueries ou sequencial scans sejam ruins por definição.

## O que a validação demonstra

1. Confere cardinalidades do dataset e exige resultado não vazio em cada cenário.
2. Compara original/candidata com `EXCEPT ALL` **nas duas direções**: inclui multiplicidade e NULLs.
3. Verifica expectativas independentes: clientes sem pedidos (Q02/Q08), empate no topo (Q05),
   raiz e categoria vazia (Q06), limite por cliente (Q07), último evento empatado (Q10).
4. Aquece cada variante uma vez e executa `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` com
   ordem alternada entre pares. Salva plano completo de cada repetição e métricas de planejamento/execução,
   linhas, buffers compartilhados e temporários.
5. Calcula mediana, mínimo, máximo e p95 empírico pelo método nearest-rank. Com poucas repetições, p95
   equivale ao máximo e **não permite conclusão estatística robusta**.

Os resultados são multiconjuntos sem ordenação externa; o desempate interno de top-N/último evento faz parte
da semântica. A equivalência em dois datasets não prova equivalência universal. Q10, por exemplo, pressupõe
o formato JSON declarado no seed. A prova precisa de revisão SQL e ampliação dos casos-limite.

Custos do planner são unidades estimadas, **não dinheiro nem tempo medido**. O relatório mantém esses valores
separados de milissegundos reais. Buffers não equivalem a CPU, consumo energético ou cobrança de cloud.
Cache aquecido e armazenamento tmpfs devem ser considerados antes de extrapolar qualquer ganho.

## Evidências e próximas etapas

Veja [a execução inicial](../../docs/experiments/complex-query-corpus/README.md), incluindo regressões e controle.
Ainda faltam integrar este corpus ao fluxo visual, acionar LLM com contexto sanitizado e usage real,
revisar propostas, ampliar escalas/repetições e registrar decisões humanas por query. Nenhuma query deste
diretório é enviada automaticamente ao banco original ou ao provedor de IA.
