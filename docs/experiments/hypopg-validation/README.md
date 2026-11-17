# Cenário descartável de validação com HypoPG

Este experimento demonstra, em PostgreSQL isolado e com dados sintéticos, que um índice hipotético é visível ao planejador em `EXPLAIN (FORMAT JSON)` sem criar um índice físico. Não mede tempo de execução e não deve usar `EXPLAIN ANALYZE` com o índice hipotético.

## Execução

Com Docker em execução, a partir da raiz do repositório:

```bash
python tools/hypopg-lab/run_hypopg_scenario.py \
  --output docs/experiments/hypopg-validation/run-$(date +%Y%m%d-%H%M%S)
```

O diretório informado recebe `plan-before.json`, `plan-after.json` e `result.json`. O *container* é removido ao final, inclusive em caso de falha. Nenhuma credencial ou conexão do ambiente monitorado é usada.

## Critério de sucesso

O `result.json` deve registrar `targetDatabaseAccessed: false`, `physicalIndexCreated: false`, um índice em `visibleHypotheticalIndexes` e seu nome em `after.indexName`. Isso confirma uma alteração de plano estimado, e não valida ganho de tempo: essa etapa continua sendo responsabilidade do *sandbox* experimental com índice físico e dados sintéticos.
