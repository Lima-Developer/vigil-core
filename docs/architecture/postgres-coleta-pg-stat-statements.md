# Coleta de contadores de `pg_stat_statements` (VIG-034)

A porta `QueryTelemetryPort` (`vigil-application`) devolve uma `TelemetryCollection`; o adapter
`PgStatStatementsTelemetryAdapter` (`com.vigil.adapters.postgres.pgstatstatements`) a implementa. Os valores são
**contadores acumulados desde o último reset**, crus. Delta, baseline e mapeamento para `ObservedQuery` ficam para cards
posteriores.

## Fluxo

1. `PostgresCapabilityProbe.inspect()` (VIG-033). Falhas do probe (`PreflightProbeException`) não são engolidas.
2. Capacidades exigidas, na ordem: versão `>= 140000`, extensão instalada, biblioteca em `shared_preload_libraries`,
   `compute_query_id` em `on` ou `auto`. A primeira ausente gera `TelemetryUnavailableException` com o
   `CapabilityCode`, antes de abrir conexão ou tocar a view.
3. Conexão do `Supplier<Connection>` recebido: `setReadOnly(true)` e `setAutoCommit(false)` (o pgjdbc só aplica
   somente-leitura dentro de transação), leitura, `rollback`, fechamento. O adapter não confia que a fábrica já fez isso.
4. `stats_reset` e `dealloc` vêm de `pg_stat_statements_info`; os contadores, de `pg_stat_statements`.

## Colunas

`dbid, userid, queryid, toplevel, calls, total_exec_time, rows, shared_blks_hit, shared_blks_read, temp_blks_read,
temp_blks_written`. Lista fixa; nunca `SELECT *`, nunca a coluna `query` na projeção. Identidade da entrada:
`(dbid, userid, queryid, toplevel)`. O texto da query só é usado no `WHERE`.

`stats_since` **não** é lido: a coluna só existe a partir do PostgreSQL 17, e o mínimo suportado é 14. O marco de reset
é o `stats_reset` global da view (`TelemetryCollection.statsReset`).

## Filtros

| Filtro | Motivo |
|---|---|
| `dbid = (SELECT oid FROM pg_database WHERE datname = current_database())` | só o banco do target |
| `query IS NOT NULL` | entradas sem texto não podem ser classificadas |
| `ltrim(query) ~* '^(select\|with)\y'` | só leitura (SELECT/WITH) |
| `query NOT ILIKE '%pg_stat_statements%'` | exclui as consultas do coletor e da própria view |

Linhas com `queryid`, contadores ou tempo nulos, contadores negativos ou `total_exec_time` NaN, infinito ou negativo são
descartadas e contadas em `skippedRows`. `calls = 0` é aceito. `queryid` negativo é válido (hash de 64 bits).

`total_exec_time` é mantido em milissegundos como `BigDecimal` (`new BigDecimal(Double.toString(v))`), sem passar por
`long` ou `Duration`.

## Visibilidade

`fullVisibility` vem de `PostgresPreflightFacts.statsReadable` (superusuário ou `pg_read_all_stats`). Sem isso, a view
mostra `<insufficient privilege>` no texto das entradas de outros usuários; elas não casam com o filtro e somem do
resultado, e `fullVisibility = false` sinaliza a coleta incompleta. Ler `shared_preload_libraries` exige ainda
`pg_read_all_settings`; sem ela o probe falha com `PreflightProbeException`.

## Limitações conhecidas

- `WITH ... INSERT/UPDATE/DELETE` passa como SELECT, pois o filtro é textual.
- Queries que comecem com comentário não casam com `^(select|with)`.
- Queries de aplicação que citem `pg_stat_statements` são excluídas. As consultas do probe (por exemplo
  `SELECT current_database()`) não citam a view e aparecem no resultado.
- Reset externo (`pg_stat_statements_reset()`) ou eviction (`pg_stat_statements.max`, padrão 5000; veja `dealloc`) tornam
  os contadores não monotônicos. O adapter não corrige; devolve `statsReset` e `dealloc` ao consumidor.
- `queryid` pode mudar entre versões do PostgreSQL ou ao recriar objetos.
- Versão mínima 14 (`toplevel` e `pg_stat_statements_info`). A IT roda no `postgres:16.4-bookworm` do laboratório; 13 e
  anteriores não são suportadas, e o ramo de versão é testado com fatos fabricados (139999, 140000, 140001).
- Nenhum `pg_stat_statements_reset`, escrita ou DDL é emitido no target.

## Testes

- Unitários sem Docker: mapper, capacidades, records, adapter com conexão e probe falsos.
- `PgStatStatementsTelemetryAdapterIT` (laboratório): `calls = 50` e `rows` de H, P1 e P2 derivados de
  `LaboratoryDataset`, exclusão das consultas do coletor, `fullVisibility` verdadeiro e falso, conexão somente leitura
  que rejeita escrita, e capacidades ausentes nomeadas.
- Mutação: `com.vigil.adapters.postgres.pgstatstatements.*` no pitest de `vigil-adapters`. Mutantes em strings SQL não são
  cobertos pelo pitest; a IT os cobre.
