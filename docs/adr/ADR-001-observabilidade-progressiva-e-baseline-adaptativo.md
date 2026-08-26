# ADR-001 — Observabilidade progressiva e baseline adaptativo

- **Status:** Aceito
- **Data:** 2026-08-19
- **Decisores:** Equipe Vigil
- **Contexto:** Vigil Core / Observabilidade PostgreSQL

## Contexto

O Vigil tem como objetivo identificar, diagnosticar e orientar a curadoria de consultas SQL que geram impacto relevante em bancos relacionais.

A detecção de degradação depende de contexto histórico: uma consulta que executa 100 mil vezes por hora pode ser normal em determinado horário, mas uma anomalia em outro. Entretanto, um produto novo não possui histórico suficiente para construir esse baseline desde seu primeiro minuto de operação.

Não é aceitável que o Vigil permaneça inoperante até coletar semanas de telemetria. Da mesma forma, não é aceitável tratar qualquer aumento de RPM ou `Sequential Scan` como um problema.

O sistema deve conseguir identificar impacto imediato e tornar-se progressivamente mais preciso à medida que aprende o comportamento do ambiente.

## Decisão

O componente de observabilidade do Vigil operará em três camadas progressivas:

1. **Detecção por impacto absoluto**
2. **Detecção por baseline provisório**
3. **Detecção de anomalia sazonal**

Cada sinal de performance emitido pelo Vigil deverá informar o nível de confiança histórica utilizado:

- `ABSENT`: não há baseline histórico;
- `PROVISIONAL`: baseline recente, ainda com baixa maturidade;
- `MATURE`: baseline histórico e sazonal suficientemente consolidado.

O Vigil não aguardará a maturação de um baseline para funcionar. Durante o período inicial, ele priorizará consultas pelo impacto técnico observado na janela atual.

## Camada 1 — Impacto absoluto

Esta camada funciona desde a primeira coleta.

Ela identifica consultas que apresentam impacto relevante no período atual, independentemente de histórico anterior. Exemplos de sinais:

- parcela relevante do tempo total de execução do banco;
- alto volume de blocos lidos;
- alto consumo de recursos temporários;
- latência acima de um SLO configurado;
- participação relevante na carga total do banco;
- aumento expressivo de chamadas combinado com custo total relevante;
- saturação de CPU, I/O, conexões ou waits associada a consultas de alto custo.

A camada não deve classificar uma consulta como ruim apenas pela presença de um `Sequential Scan`, por alto RPM ou por uma única execução lenta.

## Camada 2 — Baseline provisório

Após acumular telemetria recente, o Vigil criará um baseline de curto prazo por família de consulta.

Esse baseline poderá utilizar médias móveis e indicadores de dispersão para comparar a janela atual com períodos recentes.

Exemplos de detecção:

- crescimento relevante de latência média;
- aumento de blocos lidos por execução;
- crescimento do tempo total consumido;
- aumento de chamadas fora do comportamento recente;
- alteração de participação da consulta no workload.

Os sinais dessa camada devem usar a confiança `PROVISIONAL`, pois ainda podem sofrer influência de amostragem insuficiente ou eventos recentes.

## Camada 3 — Baseline sazonal

Após dados suficientes, o Vigil construirá um baseline contextualizado por período, como:

- hora do dia;
- dia da semana;
- janela operacional conhecida;
- calendário de picos previamente configurados.

Essa camada permite distinguir, por exemplo, um pico esperado de segunda-feira às 9h de uma degradação inesperada naquele mesmo período.

Os sinais dessa camada devem usar a confiança `MATURE`.

## Coleta e armazenamento

“Snapshot” no Vigil significa uma medição compacta de telemetria, não uma cópia do banco, das tabelas ou dos resultados das consultas.

A coleta deverá usar métricas agregadas, inicialmente a partir de `pg_stat_statements`, tais como:

- quantidade de execuções;
- tempo total e médio de execução;
- linhas retornadas;
- blocos lidos, escritos e encontrados em cache;
- uso de blocos temporários;
- tempo de planejamento, quando habilitado.

O Vigil deverá:

- armazenar snapshots detalhados por período limitado;
- manter baselines estatísticos compactos por longo prazo;
- coletar planos de execução e metadados detalhados apenas para consultas candidatas à análise;
- evitar persistir SQL bruto, parâmetros ou resultados de consultas fora do limite seguro definido pela política de privacidade.

## Critério de abertura de um caso de análise

Uma consulta poderá gerar um `AnalysisCase` quando houver evidência de impacto técnico material, por exemplo:

- impacto absoluto elevado no período atual;
- regressão relevante em relação ao baseline disponível;
- comportamento anômalo em um período sazonal comparável;
- problema crônico de alto custo e recorrência.

A geração de um caso não representa uma conclusão automática de que a query é inadequada. Ela representa uma decisão de investigá-la.

## Consequências

### Positivas

- O Vigil entrega valor desde o primeiro dia de operação.
- A precisão dos alertas aumenta naturalmente com o tempo.
- Reduz falsos positivos em picos previsíveis.
- Permite explicar o nível de evidência de cada alerta.
- Separa impacto observado de hipótese de causa.
- Não exige armazenar dados de negócio ou resultados das consultas.

### Negativas

- Durante o período inicial, o sistema terá menor capacidade de detectar regressões relativas.
- O modelo exige retenção de telemetria e manutenção de baselines.
- A definição de limiares absolutos e SLOs exige configuração por ambiente.
- Baselines sazonais exigem volume e tempo de observação suficientes.

## Alternativas consideradas

### Aguardar baseline maduro antes de operar

Rejeitada. O sistema não detectaria incidentes críticos no período inicial de implantação.

### Alertar somente por Sequential Scan ou aumento de RPM

Rejeitada. Esses sinais isolados não comprovam degradação e gerariam falsos positivos.

### Manter todos os snapshots brutos indefinidamente

Rejeitada. Aumenta custo, risco de privacidade e complexidade operacional sem necessidade para o MVP.

### Depender exclusivamente de ferramenta externa de observabilidade

Rejeitada. Integrações com ferramentas externas podem enriquecer o Vigil, mas não podem ser requisito obrigatório para seu funcionamento.

## Referências

- [PostgreSQL — pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)
- [PostgreSQL — EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html)
- [PostgreSQL — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)