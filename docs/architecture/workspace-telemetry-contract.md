# Contrato de telemetria do workspace — v1

Endpoints:

- `GET /api/workspace/telemetry`: snapshot sem cache com métricas atuais, até 120 amostras persistidas e estado do coletor;
- `POST /api/workspace/telemetry/pause`: pausa novas leituras do target;
- `POST /api/workspace/telemetry/resume`: retoma imediatamente o ciclo serial.

Nenhuma resposta expõe caminho local, senha, URL JDBC, nome de usuário ou texto SQL. O coletor usa o perfil de
menor privilégio já existente e persiste somente agregados no SQLite local.

## Envelope e semântica

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-12T15:00:00Z",
  "metrics": {},
  "samples": [],
  "collector": {
    "enabled": false,
    "paused": false,
    "collecting": false,
    "lastSuccess": null,
    "lastFailure": null,
    "consecutiveFailures": 0,
    "reason": "DISABLED"
  }
}
```

| Métrica | Fonte | Unidade | Escopo |
|---|---|---|---|
| `sqlCalls` | `pg_stat_statements` | calls | Delta de execuções SQL, não requests HTTP |
| `meanExecutionMillis` | `pg_stat_statements` | ms | Soma dos deltas de tempo / soma dos deltas de chamadas |
| `waitingSessions` | `pg_stat_activity` | sessions | Gauge de sessões com espera observada, não fila de pool |
| `localUsableBytes` | filesystem | bytes | Espaço utilizável no volume local do Vigil |
| `freeLocalStorage` | provider | bytes | Métrica do provedor do banco; nunca derivada do filesystem local |

Cada métrica carrega origem, target, instante, janela, unidade, estado, qualidade, valor e razão. Deltas usam
`{start,end}` em UTC; gauges têm `window=null`. `generatedAt` é o instante de montagem da resposta.

Estados:

- `available`: valor medido e instante original;
- `stale`: reservado ao último valor conhecido quando uma política de idade for configurada;
- `warming_up`: ainda não existem duas amostras compatíveis ou não houve chamada concluída para calcular média;
- `unsupported`: fonte não configurada ou sem adapter suportado;
- `error`: falha de leitura sem detalhes sensíveis.

Qualidade é `measured`, `partial` ou `unknown`. Ausência, zero, cobertura parcial e falha permanecem distintos.

## Coleta contínua

Quando `vigil.telemetry.enabled=true`, o backend:

1. lê contadores cumulativos de `pg_stat_statements`, sem carregar texto SQL;
2. identifica entradas por `(dbid, userid, queryid, toplevel)`;
3. calcula deltas apenas entre baselines monotônicos do mesmo epoch;
4. invalida a janela após primeira leitura, `stats_reset`, deallocation ou regressão de contador;
5. marca como parcial quando há visibilidade limitada, linhas descartadas ou churn de entradas;
6. lê apenas contagens agregadas de sessões ativas, em espera e bloqueadas em `pg_stat_activity`;
7. persiste amostras limitadas por idade e quantidade no SQLite;
8. executa serialmente, aplica backoff exponencial limitado a 15 minutos e fecha o executor no shutdown.

Configuração local padrão:

```properties
vigil.telemetry.enabled=${VIGIL_TELEMETRY_ENABLED:false}
vigil.telemetry.postgres-user=${VIGIL_TELEMETRY_POSTGRES_USER:}
vigil.telemetry.ssl-root-cert=${VIGIL_TELEMETRY_SSL_ROOT_CERT:}
vigil.telemetry.interval=${VIGIL_TELEMETRY_INTERVAL:30s}
vigil.telemetry.retention=${VIGIL_TELEMETRY_RETENTION:24h}
vigil.telemetry.maximum-samples=${VIGIL_TELEMETRY_MAXIMUM_SAMPLES:2880}
```

A senha continua sendo lida pela política existente de ambiente; não há senha em propriedades ou histórico.
`freeLocalStorage` permanece `unsupported` até existir adapter oficial para um provedor escolhido. O espaço do
filesystem do processo não é apresentado como capacidade livre do banco remoto.

## Interface

A aba Telemetria consulta o endpoint a cada 30 segundos enquanto visível, sem requisições concorrentes, com timeout
de 8 segundos. Os gráficos usam o histórico persistido retornado pelo backend. Pausar/retomar atua no scheduler
quando ele está habilitado; com o coletor desabilitado, o botão pausa apenas a atualização da tela. Gráficos têm
resumo textual, tabela acessível e traços diferentes, sem depender apenas de cor.

O schema executável está em [workspace-telemetry-v1.schema.json](workspace-telemetry-v1.schema.json).
