# Plano sequencial para a defesa do Vigil

## 1. Repaginação visual — etapa atual

- [x] Adotar navegação compacta, cards discretos e gráficos amplos das referências.
- [x] Implementar temas claro/escuro, preferência persistida e preferência do sistema.
- [x] Separar visualmente dados persistidos, fixtures e métricas ainda não coletadas.
- [x] Preparar visões de telemetria, caso, auditoria e consumo de IA.
- [x] Validar desktop/mobile, navegação por teclado e contraste nos dois temas.
- [ ] Integrar ao VIG-087 final, concluir revisão/QA e publicar PR na stack sem merge automático.

## 2. Acervo reproduzível de 10 queries complexas — executado

- [x] Criar dataset sintético PostgreSQL versionado, seed, distribuição e escala documentados.
- [x] Selecionar dez cenários distintos: CTE com agregações; subquery correlacionada; EXISTS/anti-join; múltiplos joins com skew; window functions; CTE recursiva; top-N por grupo; agregação condicional; filtros temporais com expressões; JSONB com joins e filtros.
- [x] Para cada cenário registrar SQL original, parâmetros, cardinalidades, índices, resultado esperado, hipótese e risco sem assumir que CTE/subquery seja lenta por definição.
- [x] Guardar o SQL do laboratório em artefatos controlados; não ampliar a persistência de SQL bruto do target original.
- [x] Incluir cenários sem melhoria para evitar seleção apenas de resultados positivos.

Implementação: [acervo e runner](../../tools/complex-query-lab/README.md). [Validação inicial](../experiments/complex-query-corpus/README.md): dez pares em dois seeds, equivalência multiconjunto, expectativas de borda e 80 planos medidos. Alternativas manuais; integração com IA e fluxo visual continuam pendentes. As medições são preliminares e não comprovam a tese por si só.

## 3. Próxima tarefa: contrato de telemetria e coleta contínua

- [x] Definir contrato versionado com `source`, `targetId`, `observedAt`, janela, unidade, qualidade e estado (`available`, `stale`, `unsupported`, `error`). Zero e ausência devem ser distintos.
- [ ] Derivar chamadas e latência ponderada dos deltas de pg_stat_statements, tratando reset, primeira amostra e eviction. Não chamar chamadas SQL de requests da aplicação sem instrumentação equivalente.
- [ ] Coletar sessões ativas, wait events e bloqueios de pg_stat_activity/pg_locks com privilégios mínimos. Separar espera de fila de pool, que requer outra fonte.
- [x] Medir espaço utilizável do volume SQLite local por API, sem criar arquivos, com atualização de 30 s na aba visível, pausa e histórico limitado à sessão.
- [ ] Coletar storage do PostgreSQL/provedor somente quando a fonte suportar. Não equiparar espaço local com freeLocalStorage de banco gerenciado.
- [ ] Adicionar scheduler, timeout, backoff, pausa, retenção e indicadores de última atualização. Proteger coleta sobreposta e falhas de conexão.
- [ ] Conectar séries reais à interface e substituir a prévia sintética por estados vazios quando não houver amostras.

Contrato e limites: [telemetria do workspace v1](../architecture/workspace-telemetry-contract.md). Apenas o volume local está conectado; gauges e deltas do target ainda não. A atualização no navegador não substitui o scheduler de backend.

## 4. Baseline e análise de planos

- [ ] Capturar EXPLAIN JSON seguro no target somente leitura conforme política existente.
- [x] Executar EXPLAIN ANALYZE apenas no laboratório isolado, com timeout, recursos limitados e dataset reproduzível.
- [x] Registrar custo estimado do planejador separadamente de tempo real, buffers, leituras, linhas e blocos temporários observados no plano.
- [x] Realizar aquecimento e repetições, registrar cache/configuração/versão, calcular mediana, p95 e variabilidade. Medições iniciais de três repetições; ampliar a amostra antes de inferências estatísticas.

## 5. Integração local Codex/ChatGPT e tokens

- [ ] Implementar adapter local explícito sem credenciais de escrita no target; fornecer contexto mínimo e sanitizado ao modelo.
- [ ] Tratar propostas como artefatos não confiáveis, sujeitos a parser/política e validação no sandbox antes de qualquer execução.
- [ ] Registrar por run, query, fase e tentativa: provedor, modelo exato, versão do prompt, input, output, cached input, reasoning quando reportado e duração.
- [ ] Usar contagens reportadas pelo provedor; não estimar silenciosamente nem substituir contagem ausente por zero. Guardar evidência original de usage e evitar dupla contagem de eventos cumulativos.
- [ ] Separar custo tarifado calculado de valor faturado/assinatura. Tabela de preços versionada por data e moeda, com cache e categorias faturáveis compatíveis com cada provedor.
- [ ] Comparar mesmo cenário/contexto por execução real; projeções com os mesmos tokens devem ser rotuladas, pois tokenização e saídas variam por modelo.

## 6. Validação segura e demonstração visual completa

- [ ] Comparar equivalência semântica, nulos, duplicatas, ordenação quando relevante e limites de resultados entre original e proposta.
- [ ] Medir versões original/proposta sob condições equivalentes; apresentar melhoria, regressão e ausência de ganho com evidências.
- [ ] Persistir trilha: observação → baseline → proposta → validação → comparação → decisão humana, incluindo erros e retries.
- [ ] Exibir cada fase por query com entradas/saídas permitidas, duração, tokens, custo, planos e decisão; exportar relatório reproduzível.
- [ ] Validar os dez cenários e discutir limites da tese, privacidade, validade externa e custo do próprio monitoramento.
- [ ] Ensaiar a defesa com um caso completo e resumo comparativo dos dez; preparar modo offline identificado para falhas de rede/provedor.

## Contrato visual preparado

A Visão Geral apresenta evidências medidas do corpus sintético. Casos expõe SQL, planos, fases e resultados das dez queries; a triagem existente mantém seu contrato `/api/cases/demo`. Auditoria reúne eventos do caso e registros históricos do laboratório. Telemetria separa a prévia sintética da leitura real do volume local. Uso de IA não apresenta tokens fictícios. Ainda não há scheduler de target nem execução de LLM.

## Validação visual — 12/11/2026

- Temas claro/escuro conferidos no navegador, com persistência após recarregar.
- Desktop e viewport de 375 px: sem transbordamento horizontal da página; menu horizontal rolável no mobile.
- Alternância de prévia por teclado, seletor de janela, navegação para caso/auditoria/IA e estados sem amostras verificados.
- Triagem sintética registrada na linha do tempo sem alterar o caso persistido. Caso SQLite existente lido pela API e exibido com a origem de fixture identificada.
- Sem erros de JavaScript capturados; `node --check workspace.js` e `git diff --check` aprovados.
- Revisão limitada à interface. O checkout de integração VIG-087 já continha conflito de cherry-pick em configuração Java/properties antes desta etapa; esse conflito não foi alterado nem considerado aprovado pelos testes visuais.

## Refinamento e avanço — 12/11/2026

- Sidebar com seis ícones de 18 px, mesmo eixo horizontal, rótulos, grupos e recolhimento. Header segmentado e cards nos dois temas; drawer modal no mobile.
- As seis abas têm conteúdos distintos. Visão Geral/Casos/Auditoria mostram evidências reais do corpus sintético, separadas do caso SQLite e da prévia de telemetria.
- Exportação reproduzível: `python3 tools/complex-query-lab/export_workspace.py`. Apenas o corpus sintético versionado é publicado em `corpus.json`; não inclui dados do target.
- API de telemetria v1 e leitura somente leitura do espaço utilizável local. HTTP 200, campos nulos para fontes não conectadas e `Cache-Control: no-store` conferidos na aplicação em execução.
- `verify` aprovado: 1.090 testes reportados, zero falhas/erros, três testes preexistentes desabilitados. Quatro testes novos cobrem leitura local sem criação de arquivo, volume ausente, zero legítimo e contrato de indisponibilidade.
- Validação usou JDK 21, settings Maven temporários apontando a Central (mirror corporativo retornava 401) e `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1` após falha de conexão do Ryuk em localhost. Nenhuma configuração global foi alterada.
- Interface conferida em desktop e 375 px: alinhamento expandido/recolhido, tema no drawer, deep link de query, SQL/planos, estados sem coleta e pausa/retomada da leitura local.
- Esta validação cobre a branch `codex/VIG-087-integrated-prep`. O conflito anterior do checkout de integração continua pendente; não houve merge nem publicação de PR como card concluído.
