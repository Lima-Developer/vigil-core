# Índice físico com EXPLAIN ANALYZE

Este artefato complementa o cenário de planejamento com HypoPG. Ele mede um índice físico em um PostgreSQL 16.4 descartável, criado em Docker e removido ao término. O banco monitorado não é acessado.

## Cenário

- 200.000 linhas sintéticas e determinísticas em `lab_orders`;
- consulta por `customer_id = 4242` e `status = 'COMPLETED'`;
- índice parcial de cobertura em `customer_id`, incluindo `total_cents`;
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` antes e depois da criação do índice;
- execução isolada em 18 nov. 2026.

## Resultado observado

| Medida | Antes | Depois |
| --- | ---: | ---: |
| Custo estimado total | 4.203,88 | 4,79 |
| Tempo de execução | 4,372 ms | 0,051 ms |
| Blocos compartilhados em cache | 1.439 | 1 |
| Índice usado | — | `lab_orders_customer_completed_cover_idx` |

O resultado é evidência no corpus sintético, com cache aquecido, para a consulta e distribuição avaliadas. Não constitui garantia de ganho em produção, nem substitui a avaliação de custo de escrita, seletividade e tamanho de dados do ambiente alvo.

## Reprodução

```bash
python tools/hypopg-lab/run_physical_index_scenario.py \
  --output docs/experiments/hypopg-physical-index-YYYY-MM-DD
```

Os arquivos `plan-before-physical.json`, `plan-after-physical.json` e `result.json` preservam a evidência estrutural e temporal da execução.
