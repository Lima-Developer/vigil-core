# Overhead da coleta de telemetria — `pgbench`

## Objetivo

Avaliar o requisito não funcional de baixo impacto da coleta contínua do Vigil por meio da comparação do mesmo *workload* com e sem as consultas somente leitura equivalentes ao coletor.

## Ambiente e método

| Item | Configuração |
|---|---|
| Banco | PostgreSQL 16.4 em container Docker efêmero |
| Dados | Base sintética gerada pelo `pgbench`, escala 3 |
| Carga | *workload* interno do `pgbench` em protocolo preparado |
| Concorrência | 4 clientes e 2 *threads* |
| Duração | 20 segundos por execução |
| Desenho | 3 pares intercalados; ordem alternada entre linha de base e coleta |
| Intervalo do coletor | 30 segundos, igual ao padrão do Vigil |
| Consultas do coletor | Leitura agregada de `pg_stat_statements` e `pg_stat_activity` |

O script criou o banco, habilitou `pg_stat_statements`, executou os pares e removeu o container ao fim. Não houve acesso a *target* externo, dados reais ou credenciais externas.

## Resultado

| Métrica | Sem coleta | Com coleta | Variação |
|---|---:|---:|---:|
| TPS mediano | 3.142,365 | 3.054,038 | **−2,811%** |
| Latência média mediana | 1,273 ms | 1,310 ms | **+2,907%** |
| Duração mediana do ciclo de coleta | — | 155,812 ms | — |

Foram realizados três ciclos de coleta, sem erros. Como cada execução durou menos que o intervalo configurado, cada execução com coleta incluiu um ciclo, permitindo medir seu efeito imediato sobre a carga concorrente.

## Conclusão e limites

No cenário controlado, a coleta equivalente à implementada pelo Vigil apresentou variação de aproximadamente 3% tanto em TPS quanto em latência, sem interrupção do *workload*. Isso torna o requisito de overhead **avaliado no laboratório**, não uma garantia para qualquer ambiente de produção.

O experimento mede a carga de leitura das consultas de telemetria no PostgreSQL. Ele não mede o processo Spring, configuração TLS, persistência SQLite, rede remota, volume real de `pg_stat_statements` ou comportamento sob outra topologia. Esses fatores devem ser medidos novamente antes de habilitar a coleta em produção.

Os valores estruturados e a saída bruta estão em [result.json](result.json) e [pgbench-runs.json](pgbench-runs.json). Para reproduzir ou alterar os parâmetros, use o [script do laboratório](../../../tools/telemetry-overhead-lab/run_pgbench_overhead.py).
