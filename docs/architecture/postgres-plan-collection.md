# Coleta de plano PostgreSQL (VIG-036)

O port `ExecutionPlanSource` recebe um SELECT literal e devolve apenas `PlanSummaryEvidencePayload`: tipos de nós e estimativa de linhas da raiz. `Index Scan`, `Index Only Scan` e `Bitmap Index Scan` são classificados como `INDEX_SCAN`; o último aparece na consulta H do laboratório. O JSON de `EXPLAIN` fica em memória no adapter e não integra retorno, log ou mensagens de erro. A coleta usa `EXPLAIN (FORMAT JSON)` sem `ANALYZE`, `BUFFERS` ou `VERBOSE`.

| Política | Rejeição |
|---|---|
| Texto nulo, vazio ou só com espaços | `EMPTY` |
| Mais de 8192 caracteres | `TOO_LARGE` |
| Qualquer `;`, inclusive em literal ou comentário | `MULTIPLE_STATEMENTS` |
| Primeiro token diferente de `SELECT`, inclusive `WITH`, DDL, DML e `EXPLAIN` | `NOT_SELECT` |
| NUL, `INTO`, `FOR UPDATE`, `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR KEY SHARE`, ou delimitador sem fechamento | `FORBIDDEN_CLAUSE` |
| Placeholder `$n` | `PLACEHOLDERS_UNSUPPORTED` |
| SQLSTATE `57014` ou `55P03` na coleta | `TIMEOUT` |
| JSON inválido ou plano ausente | `PLAN_UNREADABLE` |
| Outra falha SQL | `TARGET_UNAVAILABLE` |

O scanner ignora palavras de literais simples, literais `E'…'`, identificadores com aspas, comentários de linha, comentários de bloco aninhados e dollar quotes. A presença de `;` é verificada no texto bruto. Placeholders exigem parâmetros tipados e ficam para outro card; a entrada atual deve usar valores literais.

A fábrica de conexão exige TLS verificado, sessão read-only, `statement_timeout` de 5 s e `lock_timeout` de 200 ms. A coleta abre transação explícita, aplica `SET LOCAL statement_timeout = '2s'`, configura `setQueryTimeout(2)`, e executa rollback e fechamento em todos os caminhos. O papel de leitura da IT é criado apenas no teste, sem ampliar o papel compartilhado do laboratório.

Se a consulta ou leitura do plano falhar e o rollback também falhar, a rejeição conserva o motivo da primeira falha. O erro JDBC do rollback não é anexado à exceção, pois sua mensagem pode conter SQL sensível. Uma falha isolada de rollback é tratada como `TARGET_UNAVAILABLE`.
