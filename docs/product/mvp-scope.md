# Escopo do MVP

O MVP deve comprovar a jornada 
> Vigil identifica uma família de `SELECT` com impacto relevante em PostgreSQL, explica a evidência, propõe 
> uma otimização segura, valida-a fora da produção e entrega a decisão para curadoria humana.

O MVP não precisa "resolver qualquer problema de SQL". Ele precisa resolver muito bem um problema real e mensurável:
Consulta seletiva e impactante que faz leitura excessiva por ausência/inadequação de índice.

## MVP Scope - Vigil

### Problema

Consultas SQL em bancos relacionais podem passar a consumir tempo e I/O relevantes sem atenção dos responsáveis.
Um Sequential Scan isolado não prova um problema; o Vigil deve identificar impacto técnico material, investigar sua causa provável
e apoiar uma decisão humana de melhoria.

### Proposta comprovada pelo MVP

Para um único target PostgreSQL, o Vigil observa consultas SELECT, seleciona candidatas por impacto mensurável, combina evidências técnicas com inferência estruturada por modelo de linguage,
gera hipóteses de otimização e valida essas hipóteses em ambiente isolado antes de apresentá-las para curadoria humana.

### Usuário Primário

DBA, engenheiro de dados ou backend responsável pela saúde e custo técnico de um PostgreSQL/Aurora PostgreSQL (Delimitado apenas a esse Banco Relacional inicialmente)

### Fluxo Mensurável

1. O Vigil coleta duas ou mais janelas de telemetria do PostgreSQL.
2. Calcula o impacto da família de consultas na janela atual.
3. Abre um caso apenas se houver impacto material.
4. Coleta plano estimado, relações e índices existentes.
5. Sanitiza o contexto.
6. Produz findings e diagnóstico determinísticos.
7. Propõe hipótese de índice.
8. Valida a hipótese em sandbox isolado.
9. Registra aprovação, rejeição ou pedido de ajuste humano.
10. Nunca executa DDL/DML no target monitorado.

## MUST — obrigatório para comprovar o MVP

### Target, coleta e persistência

- Monitorar exatamente um target PostgreSQL, local ou iniciado por Testcontainers.
- Executar preflight de conexão, versão, permissões e disponibilidade de `pg_stat_statements`.
- Analisar somente consultas `SELECT`, inclusive CTEs cujo comando final seja `SELECT`.
- Usar `pg_stat_statements` para coletar, no mínimo:
    - quantidade de chamadas;
    - tempo total de execução;
    - tempo médio;
    - linhas retornadas;
    - blocos lidos/escritos, quando disponíveis.
- Persistir snapshots, casos de análise, evidências, hipóteses, validações e eventos em SQLite local.
  Conforme ADR-002, cada instalação do MVP atende somente um target PostgreSQL.
- Calcular deltas entre snapshots consecutivos; nunca interpretar contadores acumulados como se fossem uma janela de tempo.
- Detectar impacto absoluto desde a primeira execução, sem depender de baseline histórico. Conforme ADR-001, 
  o baseline progressivo aumenta a confiança do diagnóstico, mas não bloqueia a operação inicial
- Selecionar candidatas por critérios determinísticos de impacto e risco.
- Criar um `AnalysisCase` auditável, com histórico de estados e eventos.
- Coletar `EXPLAIN (FORMAT JSON)` sem `ANALYZE` no target monitorado.
- Coletar metadados necessários das relações envolvidas:
    - tipo da relação, incluindo tabelas e materialized views;
    - colunas relevantes;
    - índices existentes;
    - estimativa de cardinalidade.

### Privacidade e segurança

- Não persistir SQL bruto por padrão.
- Não registrar SQL bruto em logs, eventos, erros ou métricas.
- Sanitizar e minimizar o contexto antes de qualquer análise externa ao core.
- Bloquear e registrar como não analisável qualquer conteúdo que não possa ser sanitizado com segurança.
- Enviar ao modelo exclusivamente contexto sanitizado, minimizado e associado a evidências do caso.
- Garantir que nenhuma aprovação humana ou resultado de inferência execute alterações no banco monitorado.

### Diagnóstico, inferência e validação

- Implementar ao menos uma regra determinística de evidência:
    - `POSSIBLE_AVOIDABLE_SEQ_SCAN`.
- Implementar uma porta independente de fornecedor: `InferencePort`.
- Implementar um provider de inferência para o experimento:
    - modelo local compatível com Ollama/vLLM; **ou**
    - Amazon Bedrock.
- Exigir saída estruturada para diagnóstico e hipótese.
- Validar schema, tipos, limites e referências a evidências antes de aceitar a resposta do modelo.
- Rejeitar respostas sem grounding em evidências reais do caso.
- Tratar timeout, indisponibilidade e saída inválida como falhas auditáveis.
- Permitir que o diagnóstico conclua que uma intervenção não é recomendada.
- Gerar, quando aplicável, uma hipótese de otimização, inicialmente focada em índice.
- Validar hipóteses em PostgreSQL isolado iniciado por Testcontainers.
- Classificar a validação como `VALIDATED`, `INVALIDATED` ou `INCONCLUSIVE`.
- Registrar decisão humana com ator, motivo e data.

### Avaliação acadêmica

- Possuir dataset sintético, reproduzível, versionado e rotulado.
- Possuir testes automatizados de domínio, sanitização, persistência, coleta e validação.
- Avaliar pelo menos um modelo no dataset sintético.
- Comparar, no mínimo:
    - regras determinísticas;
    - fluxo híbrido: regras + inferência + validação.
- Medir precisão das candidatas, qualidade do diagnóstico, qualidade das hipóteses, taxa de bloqueio inseguro, falhas de inferência, latência e resultado da validação.

## SHOULD — desejável após concluir o MUST

- Baseline provisório por média móvel recente.
- Regras adicionais para joins caros, cardinalidade incorreta e temp I/O.
- Adapter local compatível com Ollama/vLLM, caso Bedrock tenha sido escolhido no MUST.
- Adapter Amazon Bedrock, caso provider local tenha sido escolhido no MUST.
- Comparação experimental B0–B3:
    - B0: triagem manual;
    - B1: regras determinísticas;
    - B2: LLM sem validação;
    - B3: fluxo híbrido com validação.
- Métricas internas de duração, falhas, bloqueios de privacidade e custo estimado de inferência.
- API REST para consultar e revisar casos.
- Interface web simples para curadoria humana.
- Empacotamento com Docker Compose.
- HypoPG como mecanismo adicional de simulação de índices.
- Suportar mais de um provider ou modelo simultaneamente.
- Expandir o diagnóstico determinístico para novos tipos de problema.

## FORA DE ESCOPO DO MVP

- Monitorar múltiplos targets simultaneamente.
- Executar `CREATE INDEX`, `ALTER TABLE` ou qualquer DDL/DML no target monitorado.
- Executar `EXPLAIN ANALYZE` indiscriminadamente em produção.
- Usar dados reais da empresa no laboratório, dataset ou prompts.
- Persistir SQL bruto como comportamento padrão.
- Dashboard corporativo multiusuário.
- Alta disponibilidade, replicação ou persistência centralizada.
- Detecção sazonal madura por dia, horário ou período de negócio.
- Diagnosticar todos os tipos de problema do PostgreSQL.
- Suporte inicial a Redshift, MySQL, Oracle ou SQL Server.
- Garantir economia financeira real em cloud; o MVP mede impacto técnico e potencial de redução.
- HMAC, KMS e integração completa com secret managers.
- Agentes autônomos, tool calling e arquiteturas multiagente.
- Permitir que o LLM selecione sozinho candidatas ou execute ações no banco.
- Reconstruir um clone fiel do banco produtivo, incluindo dados reais e volume idêntico.

### Cenários Mínimos de Avaliação

1. Tabela pequena com Sequential Scan correto:
   Não deve gerar finding de scan evitável

2. Tabela grande com filtro seletivo sem índice:
   Deve virar candidata, produzir finding e testar hipótese de índice.

3. Query de alto RPM mas baixo impacto total:
   Não deve gerar caso apenas pelo volume de chamadas.

4. Query de alto impacto total:
   Deve gerar caso mesmo sem baseline histórico.

5. Query insegura ou não interpretável:
   Deve ser bloqueada antes de seguir para inferência externa.

### Métricas de Sucesso

As metas abaixo são critérios do experimento, não resultados presumidos.

- Precisão e recall da seleção de candidatas no dataset sintético.
- Precisão dos findings determinísticos.
- Taxa de recomendações inseguras: alvo igual a zero.
- Taxa de vazamento no corpus de sanitizações: alvo igual a zero.
- Tempo entre a segunda coleta e abertura do caso.
- Duração e overhead da coleta no ambiente de laboratório.
- Percentual de hipóteses corretamente classificadas como validadas, invalidadas ou inconclusivas.
- Para modelos de IA, comparar qualidade, latência e custo contra as regras, sem permitir que o modelo gere recomendação sem validação.

### Critério de Aceite

O documento deixa explícito usuário, problema, fluxo, MUST, SHOULD, fora de escopo, cenários de avaliação e métricas.
Uma pessa externa consegue entender o que o Vigil comprova sem conhecer o restante do projeto.

## Decisões Arquiteturais Vinculantes

O escopo deste MVP implementa as decisões registradas nos ADRs abaixo. Qualquer alteração que contradiga essas decisões deve resultar em revisão ou criação de um novo ADR.

- [ADR-001 — Observabilidade progressiva e baseline adaptativo](../adr/ADR-001-observabilidade-progressiva-e-baseline-adaptativo.md)  
  Define que o Vigil funciona desde o primeiro snapshot por meio de sinais de impacto absoluto. O baseline é progressivo: inicialmente ausente, depois provisório e, futuramente, maduro. Portanto, a ausência de histórico não impede a identificação de casos potencialmente danosos.

- [ADR-002 — SQLite como persistência padrão da instalação local do Vigil](../adr/ADR-002-sqlite-persistencia-local.md)  
  Define que o MVP opera sobre um único target PostgreSQL e usa SQLite local como armazenamento operacional de snapshots, casos, evidências, eventos e resultados de validação. A centralização multi-target e o uso de PostgreSQL dedicado permanecem fora do escopo inicial.