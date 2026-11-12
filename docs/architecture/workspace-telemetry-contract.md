# Contrato de telemetria do workspace — v1

Endpoint implementado: `GET /api/workspace/telemetry`, com `Cache-Control: no-store`.
Não executa SQL no target. Nesta entrega somente `localUsableBytes` faz uma leitura real do volume
que contém o SQLite do Vigil, sem criar arquivos de sonda. As demais fontes estão explicitamente indisponíveis.

## Envelope e semântica

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-12T15:00:00Z",
  "metrics": {
    "localUsableBytes": {
      "source": "filesystem",
      "targetId": "vigil-local-storage",
      "observedAt": "2026-11-12T15:00:00Z",
      "window": null,
      "unit": "bytes",
      "state": "available",
      "quality": "measured",
      "value": 0,
      "reason": null
    }
  }
}
```

Exemplo abreviado: zero bytes é um valor válido e não representa ausência de leitura. O payload real contém
as cinco métricas da tabela abaixo. Nenhuma resposta expõe caminho local, senha, URL JDBC ou SQL do target.

| Métrica | Fonte | Unidade | Escopo |
|---|---|---|---|
| sqlCalls | pg_stat_statements | calls | Delta de execuções SQL, não requests HTTP |
| meanExecutionMillis | pg_stat_statements | ms | Soma dos deltas de tempo / soma dos deltas de chamadas |
| waitingSessions | pg_stat_activity | sessions | Sessões com espera observada, não fila de pool |
| localUsableBytes | filesystem | bytes | Espaço utilizável no volume local do Vigil |
| freeLocalStorage | provider | bytes | Métrica do provedor do banco; nunca derivada do filesystem local |

Cada métrica carrega `source`, `targetId`, `observedAt`, `window`, `unit`, `state`, `quality`,
`value` e `reason`. `generatedAt` indica a montagem da resposta, não a última observação.
Gauges pontuais têm `window=null`; deltas usam `{start,end}` em UTC com `start<end`.

Estados e valores:

- `available`: valor numérico finito >= 0, timestamp válido, razão nula.
- `stale`: último valor conhecido e timestamp original, com razão explícita; nunca atualizar artificialmente a data.
- `unsupported`: fonte/capacidade não conectada; valor, timestamp e janela nulos.
- `error`: falha de leitura; valor e timestamp nulos, código de razão sem detalhes sensíveis.
- `warming_up` (reservado ao futuro coletor): ainda sem duas amostras válidas após início/reset; valor nulo.

Qualidade: `measured`, `partial` ou `unknown`. Qualidade parcial requer razão explicando cobertura.
Ausência, permissão parcial e zero devem continuar distinguíveis. O backend atual emite `measured` ou `unknown`.

## Atualização da interface implementada

Fora da prévia visual, a aba Telemetria lê o endpoint a cada 30 s, somente enquanto estiver visível.
Existe pausa manual, cancelamento ao trocar de aba, timeout de 8 s e proibição de requests sobrepostos.
O gráfico local retém no máximo 60 pontos **em memória da sessão do navegador**. Recarregar limpa esse histórico.
Séries de prévia são separadas, identificadas e não são acrescentadas ao histórico real.
Falhas exibem indisponibilidade; os pontos anteriores, quando existentes, continuam identificados por horário.

Isto ainda **não é um scheduler de coleta de PostgreSQL no backend**, nem persistência/retencão histórica.

## Próxima implementação: coletor do target

1. Reusar a porta de telemetria e as conexões de menor privilégio já existentes, sem coletar texto SQL bruto.
2. Identificar cada contador por banco/usuário/queryid/toplevel; comparar amostras de uma mesma origem.
3. Na primeira amostra, reinício, alteração de stats_reset, regressão de contadores ou eviction/dealloc,
   invalidar a janela afetada e reconstruir baseline. Não representar deltas negativos como zero.
4. Uma entrada nova não tem baseline confiável; uma entrada ausente pode ter sido removida. A soma somente
   pode representar o total se a cobertura for conhecida; caso contrário, marcar parcial ou indisponível.
5. Latência agregada é ponderada por chamadas. Com delta de chamadas zero, a latência é ausente,
   não zero e não média simples de médias. Definir tempo e escopo para chamadas concluídas.
6. Sessões/esperas e bloqueios são gauges de fontes próprias; leituras não atômicas devem declarar timestamps.
7. Introduzir execução serial, intervalo configurável, timeout, backoff limitado, pausa e retenção persistida.
   Testar falha de conexão, recuperação, resets, permissão parcial e encerramento ordenado.
8. Integrar provedor cloud apenas mediante adapter específico. Deixar `freeLocalStorage` indisponível onde não houver suporte.

O schema executável está em [workspace-telemetry-v1.schema.json](workspace-telemetry-v1.schema.json).
