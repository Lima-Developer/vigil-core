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

Se a consulta ou leitura do plano falhar e o rollback ou fechamento também falhar, a rejeição conserva o motivo da primeira falha. Erros JDBC de limpeza não são anexados à exceção, pois suas mensagens podem conter SQL sensível. Uma falha isolada de rollback ou fechamento é tratada como `TARGET_UNAVAILABLE`. A falha de abertura preserva a classificação e o SQLSTATE em uma nova `TargetConnectionFailure`, sem causa JDBC.

## Justificativa dos mutantes de fronteira

O PIT do guard deixa quatro mutantes `SURVIVED` de `ConditionalsBoundaryMutator`, todos equivalentes para as entradas que chegam aos respectivos ramos:

| Linha do guard | Limite alterado | Por que o resultado é igual |
|---|---|---|
| Loop de comentário de bloco | `i < length` para `i <= length` | Com comentário fechado, `depth` é zero e o loop termina. Sem fechamento, ambos os caminhos rejeitam `FORBIDDEN_CLAUSE`; a versão mutada faz uma iteração adicional. |
| Detecção do prefixo `E` | `i > 0` para `i >= 0` | Uma aspa na posição zero é rejeitada antes desse ramo pela regra do primeiro token. Nas demais posições as condições são iguais. |
| Barra invertida em `E'…'` | `i < length` para `i <= length` | No fim do texto, ambos deixam o literal sem fechamento e rejeitam `FORBIDDEN_CLAUSE`. |
| Fechamento de dollar quote | `close < 0` para `close <= 0` | A busca começa após o delimitador de abertura, então um fechamento encontrado nunca está na posição zero. |

Os testes diretos de fechamento de comentário, prefixo `e` e caracteres antes de `SELECT` matam as mutações comportamentais adjacentes. O relatório PIT das três classes de VIG-036 não apresenta outros sobreviventes.
