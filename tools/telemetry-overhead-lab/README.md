# Medição de overhead de coleta

Este experimento mede, em um PostgreSQL descartável, a diferença de desempenho do mesmo *workload* do `pgbench` sem e com as consultas somente leitura equivalentes às executadas pelos adapters de telemetria do Vigil.

Ele registra TPS, latência média, duração de cada ciclo de coleta e a variação percentual entre as medianas das execuções. A ordem das condições é alternada a cada rodada para reduzir viés de aquecimento de cache.

## Segurança e escopo

- usa somente um container PostgreSQL efêmero (`postgres:16.4`);
- cria e remove esse container ao término;
- usa dados produzidos pelo `pgbench`;
- não acessa o banco monitorado, credenciais externas ou dados reais;
- executa as consultas de `pg_stat_statements` e `pg_stat_activity` equivalentes ao `PgStatStatementsTelemetryAdapter` e ao `PostgresDatabaseActivityAdapter`.

O resultado mede a carga de leitura no banco causada pela coleta. Ele **não** mede o custo completo do processo Spring, TLS, persistência SQLite ou uma topologia remota. Essas limitações são preservadas no `result.json` para evitar extrapolação indevida.

## Pré-requisitos

- Docker em execução;
- imagem `postgres:16.4` disponível ou passível de download.

## Execução

Na raiz do repositório:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
python tools/telemetry-overhead-lab/run_pgbench_overhead.py `
  --output ".\docs\experiments\telemetry-overhead-$stamp"
```

O padrão usa escala 5, 8 clientes, 4 threads, 30 segundos por execução e três pares de medições. Para uma rodada curta de verificação:

```powershell
python tools/telemetry-overhead-lab/run_pgbench_overhead.py `
  --output ".\docs\experiments\telemetry-overhead-smoke" `
  --seconds 10 `
  --rounds 2
```

## Artefatos

- `result.json`: parâmetros, medianas e cálculo do overhead;
- `pgbench-runs.json`: saída bruta de cada execução e duração de cada ciclo de coleta.

Interprete `throughputChangePercent` negativo como redução de TPS e `latencyChangePercent` positivo como aumento de latência. O relatório deve sempre apresentar configuração, versão, duração, número de rodadas e limitações metodológicas junto dos números.
