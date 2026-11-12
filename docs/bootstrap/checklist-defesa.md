# Plano sequencial para a defesa do Vigil

## 1. Repaginação visual

- [x] Adotar navegação compacta, cards discretos e gráficos amplos das referências.
- [x] Implementar temas claro/escuro, preferência persistida e preferência do sistema.
- [x] Separar visualmente dados persistidos, fixtures e métricas ainda não coletadas.
- [x] Preparar visões de telemetria, caso, auditoria e consumo de IA.
- [x] Validar desktop/mobile, navegação por teclado e contraste nos dois temas.
- [ ] Publicar o PR final na stack sem merge automático (após `verify` e revisão do diff).

## 2. Acervo reproduzível de 10 queries complexas — executado

- [x] Criar dataset sintético PostgreSQL versionado, seed, distribuição e escala documentados.
- [x] Selecionar dez cenários distintos: CTE com agregações; subquery correlacionada; EXISTS/anti-join; múltiplos joins com skew; window functions; CTE recursiva; top-N por grupo; agregação condicional; filtros temporais com expressões; JSONB com joins e filtros.
- [x] Para cada cenário registrar SQL original, parâmetros, cardinalidades, índices, resultado esperado, hipótese e risco sem assumir que CTE/subquery seja lenta por definição.
- [x] Guardar o SQL do laboratório em artefatos controlados; não ampliar a persistência de SQL bruto do target original.
- [x] Incluir cenários sem melhoria para evitar seleção apenas de resultados positivos.

Implementação: [acervo e runner](../../tools/complex-query-lab/README.md). [Validação ampliada](../experiments/complex-query-corpus/README.md): dez pares em dois seeds, escala 3, sete repetições por variante, equivalência multiconjunto, expectativas de borda e 280 planos medidos. As alternativas continuam manuais; a IA faz curadoria limitada entre versões validadas. As medições não comprovam a tese por si só.

## 3. Contrato de telemetria e coleta contínua

- [x] Definir contrato versionado com `source`, `targetId`, `observedAt`, janela, unidade, qualidade e estado (`available`, `stale`, `unsupported`, `error`). Zero e ausência devem ser distintos.
- [x] Derivar chamadas e latência ponderada dos deltas de pg_stat_statements, tratando reset, primeira amostra, regressão, deallocation e cobertura parcial. Não chamar chamadas SQL de requests da aplicação.
- [x] Coletar gauges agregados de sessões ativas, em espera e bloqueadas de pg_stat_activity com privilégios mínimos. Espera permanece separada de fila de pool.
- [x] Medir espaço utilizável do volume SQLite local por API, sem criar arquivos, com atualização de 30 s na aba visível.
- [x] Manter storage do PostgreSQL/provedor como `unsupported` onde não existe adapter oficial. Não equiparar espaço local com freeLocalStorage de banco gerenciado.
- [x] Adicionar execução serial, timeout JDBC, backoff limitado, pausa, retenção SQLite e indicadores de última atualização. Proteger coleta sobreposta e falhas de conexão.
- [x] Conectar séries persistidas à interface e usar estados vazios quando não houver amostras; prévia sintética permanece opcional e identificada.

Contrato e limites: [telemetria do workspace v1](../architecture/workspace-telemetry-contract.md). O coletor do target é opcional e desabilitado por padrão; quando habilitado, persiste somente agregados seguros. Provider storage continua indisponível sem fonte suportada.

## 4. Baseline e análise de planos

- [x] Capturar EXPLAIN JSON seguro no target somente leitura conforme política existente.
- [x] Executar EXPLAIN ANALYZE apenas no laboratório isolado, com timeout, recursos limitados e dataset reproduzível.
- [x] Registrar custo estimado do planejador separadamente de tempo real, buffers, leituras, linhas e blocos temporários observados no plano.
- [x] Realizar aquecimento e repetições, registrar cache/configuração/versão, calcular mediana, p95 e variabilidade. Execução principal com sete repetições por variante, escala 3 e dois seeds.

## 5. Integração local Codex/ChatGPT e tokens

- [x] Implementar runner local explícito sem credenciais de escrita no target; fornecer apenas SQL sintético, métricas e planos compactados.
- [x] Limitar a IA à curadoria entre duas variantes versionadas e já validadas. Nenhum SQL produzido por modelo é executado.
- [x] Registrar por run e query: provedor, modelo exato, versão do prompt, input, output, cached input, cache write, reasoning quando reportado e duração.
- [x] Usar contagens do evento final `turn.completed`, preservar JSONL bruto e evitar dupla contagem de campos que são subconjuntos.
- [x] Separar projeção tarifária de valor faturado/assinatura. Tabela de preços em USD versionada em 12/11/2026.
- [x] Rotular a projeção Claude com os mesmos tokens como não executada. A CLI Claude não estava instalada; não há comparação real entre provedores.

## 6. Validação segura e demonstração visual completa

- [x] Comparar equivalência semântica, nulos, duplicatas, ordenação relevante e limites entre original e candidata em dois seeds.
- [x] Medir versões sob condições equivalentes e apresentar melhoria, regressão, controle e mudança de sinal com evidências.
- [x] Preservar trilha técnica: corpus → planos → equivalência → medição → curadoria IA → usage. A aprovação humana permanece explicitamente pendente.
- [x] Exibir cada fase por query com duração, tokens, custo projetado, planos e decisão da IA; exportar relatório JSON reproduzível.
- [x] Validar os dez cenários e documentar limites da tese, privacidade, validade externa e custo do monitoramento.
- [ ] Realizar o ensaio humano da defesa e registrar parecer final sobre pelo menos um caso. O dashboard e o modo offline já estão preparados.

## Contrato visual preparado

A Visão Geral apresenta evidências medidas do corpus sintético. Casos expõe SQL, planos, fases, curadoria e resultados das dez queries; a triagem existente mantém seu contrato `/api/cases/demo`. Auditoria reúne eventos do caso, laboratório e usage da IA. Telemetria separa prévia sintética, volume local e coleta opcional do target. Uso de IA apresenta somente tokens reportados e custos projetados identificados.

## Validação visual — 12/11/2026

- Temas claro/escuro conferidos no navegador, com persistência após recarregar.
- Desktop e viewport de 375 px: sem transbordamento horizontal da página; menu horizontal rolável no mobile.
- Alternância de prévia por teclado, seletor de janela, navegação para caso/auditoria/IA e estados sem amostras verificados.
- Triagem sintética registrada na linha do tempo sem alterar o caso persistido. Caso SQLite existente lido pela API e exibido com a origem de fixture identificada.
- Sem erros de JavaScript capturados; `node --check workspace.js` e `git diff --check` aprovados.
- Esta rodada visual ainda não cobria os blocos posteriores de telemetria/IA; eles foram validados na seção de conclusão abaixo.

## Refinamento e avanço — 12/11/2026

- Sidebar com seis ícones de 18 px, mesmo eixo horizontal, rótulos, grupos e recolhimento. Header segmentado e cards nos dois temas; drawer modal no mobile.
- As seis abas têm conteúdos distintos. Visão Geral/Casos/Auditoria mostram evidências reais do corpus sintético, separadas do caso SQLite e da prévia de telemetria.
- Exportação reproduzível: `python3 tools/complex-query-lab/export_workspace.py`. Apenas o corpus sintético versionado é publicado em `corpus.json`; não inclui dados do target.
- API de telemetria v1 e leitura somente leitura do espaço utilizável local. HTTP 200, campos nulos para fontes não conectadas e `Cache-Control: no-store` conferidos na aplicação em execução.
- `verify` final aprovado: 1.100 testes reportados, zero falhas/erros e três testes preexistentes desabilitados. A cobertura adicionada inclui deltas/reset de telemetria, atividade agregada do PostgreSQL, retenção SQLite, ciclo do coletor e contrato HTTP de indisponibilidade.
- Validação usou JDK 21, settings Maven temporários apontando a Central (mirror corporativo retornava 401) e `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1` após falha de conexão do Ryuk em localhost. Nenhuma configuração global foi alterada.
- Interface conferida em desktop e 375 px: alinhamento expandido/recolhido, tema no drawer, deep link de query, SQL/planos, estados sem coleta e pausa/retomada da leitura local.
- Esta validação cobre a branch `codex/VIG-087-integrated-prep`; o gate final terminou com `BUILD SUCCESS` em 12/11/2026. A publicação do PR permanece como o último passo técnico externo.

## Conclusão dos blocos técnicos — 12/11/2026

- Coletor PostgreSQL opcional com baseline/deltas, gauges agregados, retenção SQLite, pausa, backoff e shutdown.
- API v1 e interface validadas com o coletor desabilitado: valores ausentes permanecem `unsupported`, não zero.
- Execução principal do corpus: 20/20 equivalências, 280 planos e sete repetições por variante.
- Dez curadorias reais com `gpt-6-sol`: 144.011 input, 80.000 cached input, 2.236 output e 158 reasoning reportados; 83,928 s acumulados.
- Projeção API datada: US$ 0,083191 para GPT-6 Sol. Claude Sonnet 5.5: US$ 0,158382 usando os mesmos tokens, explicitamente sem execução.
- Validação visual em tema escuro e claro, estados vazios e relatório de Uso de IA na build real em `127.0.0.1`.
