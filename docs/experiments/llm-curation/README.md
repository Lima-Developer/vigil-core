# Curadoria assistida por IA — 12/11/2026

Dez execuções reais do Codex avaliaram as duas variantes SQL versionadas de cada cenário sintético. O modelo não
gerou SQL, não recebeu credenciais ou dados do target e não teve autorização para executar comandos. Cada chamada
usou `gpt-6-sol`, reasoning `low`, sessão efêmera, sandbox somente leitura e resposta JSON validada por schema.

Este recorte demonstra IA como **curador de alternativas previamente limitadas e validadas**, não como executor
autônomo de SQL. A alternativa candidata continua sendo manual e foi executada somente no laboratório isolado.

## Resultado medido

| Medida | Valor reportado |
|---|---:|
| Runs concluídos | 10 |
| Duração acumulada | 83,928 s |
| Input total | 144.011 tokens |
| Input em cache | 80.000 tokens |
| Cache write | 0 tokens |
| Output total | 2.236 tokens |
| Reasoning dentro do output | 158 tokens |

`cached_input_tokens` é subconjunto de `input_tokens`; reasoning é subconjunto de output e não é somado novamente.
Os eventos `turn.completed` originais estão preservados em
[`codex-gpt-6-sol`](codex-gpt-6-sol/), junto das respostas estruturadas e do manifest.

Decisões: candidata em Q02, Q04, Q06, Q08, Q09 e Q10; original em Q01, Q03, Q05 e Q07. O controle Q05 foi mantido
como original. Q07, cuja candidata regrediu nos dois seeds, também foi mantida. Isso reduz o risco de apresentar toda
reescrita como melhoria.

## Custo: projeção, não fatura

O CLI foi autenticado pela assinatura do usuário e não reportou custo faturado por run. Portanto `billedCostUsd`
permanece nulo. Aplicando aos tokens reportados as tarifas API padrão, short-context, vigentes em 12/11/2026:

- GPT-6 Sol: **US$ 0,083191** de equivalência tarifária;
- Claude Sonnet 5.5: **US$ 0,158382** usando a mesma quantidade de tokens apenas como projeção.

A projeção Claude não é benchmark entre provedores: a CLI `claude` não estava instalada, logo não houve chamada,
tokenização, latência ou resposta Claude. O arquivo de preços versionado e as URLs oficiais ficam em
[`pricing-2026-11-12.json`](../../../tools/complex-query-lab/pricing-2026-11-12.json). O cálculo reproduzível é:

```sh
python3 tools/complex-query-lab/summarize_curation.py \
  --run-directory docs/experiments/llm-curation/codex-gpt-6-sol \
  --pricing tools/complex-query-lab/pricing-2026-11-12.json \
  --output docs/experiments/llm-curation/summary.json
```

## Limites e decisão humana

- Equivalência observada em dois seeds não prova equivalência universal.
- Medições em container/tmpfs não representam produção ou cobrança cloud.
- A IA recebeu métricas após a validação; portanto este experimento mede curadoria baseada em evidências, não geração.
- Confiança é autorrelatada pelo modelo e não uma probabilidade calibrada.
- A aprovação final continua humana e deve registrar motivação, inclusive quando rejeitar uma recomendação da IA.
