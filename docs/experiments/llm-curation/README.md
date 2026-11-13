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

## Próxima coleta: Claude Code em outra máquina

O repositório já contém um runner para executar **as mesmas 10 decisões pareadas**, com o mesmo contexto sintético,
schema de resposta e artefatos por caso. Ele não autentica, não instala o Claude e não grava credenciais: a máquina
que tiver o Claude Code autenticado é a única que precisa dessa configuração. A referência oficial do CLI documenta
`claude -p`, `--output-format json` e `--model`.

Na outra máquina, clone esta mesma revisão do repositório, entre na raiz e confirme a autenticação interativamente
com `claude`. Em seguida, primeiro gere a prévia sem consumir janela:

```sh
python3 tools/complex-query-lab/run_claude_curation.py \
  --output /tmp/vigil-claude-dry-run \
  --model sonnet \
  --dry-run
```

Se os dez `*.prompt.json` estiverem corretos, execute a coleta real em um diretório novo:

```sh
python3 tools/complex-query-lab/run_claude_curation.py \
  --output /tmp/vigil-claude-$(date +%Y%m%d%H%M%S) \
  --model sonnet
```

No Windows, o runner reconhece automaticamente instalações npm que expõem `claude.cmd` ou `claude.ps1`; use sempre um diretório de
saída novo. O dry-run é silencioso no terminal por intenção: confirme-o por `manifest.json` com `status: planned` e
dez itens em `runs`.

O resultado preserva o envelope JSON bruto devolvido pelo CLI, a resposta validada, duração do host e o campo de
usage quando o provedor o expuser. A fatura da assinatura/API continua separada: o runner nunca transforma tokens em
uma cobrança real. Copie o diretório de saída para `docs/experiments/llm-curation/` antes de versioná-lo.

## Próxima coleta: Qwen3.8-27B hospedado no OpenRouter

Sim: o OpenRouter oferece endpoint OpenAI-compatível e o `qwen/qwen3.8-27b` suporta saída estruturada. Esta coleta
não exige GPU local. Ela é um benchmark de um modelo de pesos abertos **hospedado**, e não uma execução local: só o
corpus sintético/versionado é enviado, nunca credenciais, SQL do target ou dados do banco observado.

Em 13/11/2026, a página pública do modelo informava **US$ 0,035/M tokens de entrada** e **US$ 1,50/M de saída**.
Usando os 144.011 tokens de entrada e 2.236 de saída já observados no recorte Codex, a projeção linear é
**US$ 0,008394 para 10 decisões pareadas**. Para 20 análises individuais de variante com a mesma média, a projeção
é **US$ 0,016789**. Isto é uma estimativa de planejamento, não uma fatura nem uma garantia de tokenização igual.

O Qwen3.8-27B é a escolha padrão porque é mais novo, tem janela de 1M tokens e custa cerca de 31% menos que o
Qwen3-32B nesta carga histórica de entrada alta/saída curta (US$ 0,012147 no mesmo cálculo). Isso não comprova que
ele seja superior a GPT-6 Luna ou a qualquer outro modelo: a comparação defensável será a taxa de JSON válido,
decisão segura, abstenção em Q01 e aderência às evidências do nosso próprio corpus. O runner também aceita o baseline
anterior com `--model qwen/qwen3-32b`.

O runner fixa também um teto independente mais conservador: 10 chamadas × 18.000 tokens de entrada × 700 de saída
resultam em no máximo **US$ 0,016800** pelas tarifas pinadas. O padrão `--max-cost-usd 0.05` é deliberadamente maior
que esse teto; reduza-o se desejar. Preço e rota podem mudar, então confira a página do modelo antes da execução.

Primeiro gere somente os prompts e o teto calculado (sem rede e sem custo):

```sh
python3 tools/complex-query-lab/run_openrouter_curation.py \
  --output /tmp/vigil-qwen-dry-run \
  --dry-run
```

Depois de criar uma chave restrita no OpenRouter, exponha-a apenas na sessão do shell e faça a coleta:

```sh
export OPENROUTER_API_KEY='cole-a-chave-apenas-nesta-sessao'
python3 tools/complex-query-lab/run_openrouter_curation.py \
  --output /tmp/vigil-qwen-$(date +%Y%m%d%H%M%S) \
  --max-cost-usd 0.05
```

`OPENROUTER_API_KEY` é lida apenas do ambiente, nunca vai para manifest, prompts ou resposta bruta. O manifest grava
o modelo efetivo, preços pinados, limite, custo máximo calculado, tokens/usage retornados pelo provedor e duração de
cada chamada. Para máxima previsibilidade, execute inicialmente com `--queries Q01 Q02`; para o acervo completo,
omita `--queries`.

Fontes: [OpenRouter Chat Completions](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request),
[Qwen3.8-27B no OpenRouter](https://openrouter.ai/qwen/qwen3.8-27b) e
[referência do Claude Code](https://docs.anthropic.com/en/docs/claude-code/cli-usage).
