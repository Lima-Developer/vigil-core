# Fundamentos do Vigil

> Documento de linguagem ubíqua, escopo e decisões que orientam o core do Vigil.

## 1. Propósito e escopo

O Vigil é uma aplicação leve de observabilidade, diagnóstico, validação e curadoria humana para consultas de bancos relacionais. O primeiro adaptador suporta PostgreSQL e Aurora PostgreSQL.

O problema que ele trata não é a simples existência de um `Sequential Scan` ou o crescimento de RPM. O foco é uma família de consultas que, de modo **material e comprovável**, esteja degradando o banco por consumo de tempo, I/O, concorrência ou recursos temporários — seja por uma regressão silenciosa, seja por um problema crônico sem priorização.

### Escopo inicial

- Uma instalação do Vigil monitora um único *target*: um PostgreSQL ou cluster Aurora PostgreSQL.
- A primeira versão analisa consultas de leitura (`SELECT`, inclusive CTEs cujo comando final é `SELECT`).
- O Vigil não aplica DDL ou DML no banco monitorado. Uma aprovação humana não cria índices nem altera queries automaticamente.
- A instalação é local ao ambiente do cliente e usa SQLite em volume persistente como persistência padrão.
- Materialized views são tratadas como relações físicas relevantes, junto de tabelas, tabelas particionadas e outras relações suportadas pelo PostgreSQL.

`INSERT`, `UPDATE`, `DELETE`, locks, bloat e autovacuum são problemas importantes, mas estão fora do recorte inicial: exigem sinais, permissões e regras próprias.

### Princípios

1. **Impacto antes de padrão.** `Sequential Scan`, RPM alto ou custo estimado isolados não comprovam problema.
2. **Evidência antes de inferência.** O modelo de IA não é fonte de verdade técnica.
3. **Privacidade por padrão.** SQL bruto, parâmetros e resultados não são persistidos ou enviados por padrão.
4. **Falhar fechado para egress sensível.** Se o contexto não puder ser tratado de forma segura, ele não segue para inferência externa.
5. **Humano decide; Vigil recomenda.** Nenhuma sugestão é aplicada automaticamente.
6. **O core não conhece fornecedor.** JDBC, SQLite, Spring, Bedrock e Ollama pertencem aos adaptadores, não ao domínio.

## 2. Fluxo principal

```text
Telemetria do target
        ↓
Consulta observada
        ↓
Avaliação de impacto e baseline
        ↓
Consulta candidata
        ↓
Caso de análise auditável
        ↓
Evidências → sanitização → diagnóstico → hipótese
        ↓
Validação isolada
        ↓
Recomendação → decisão humana → arquivamento
```

O fluxo deve preservar esta distinção:

| Elemento | Pergunta que responde |
|---|---|
| Estado | Em que etapa controlada o caso está? |
| Evento | O que aconteceu? |
| Evidência | Em qual fato verificável a conclusão se apoia? |
| Finding | Qual sinal ou problema técnico foi identificado? |
| Diagnóstico | Como os findings e evidências são interpretados juntos? |

## 3. Termos principais

| Termo | Definição no Vigil | Não é |
|---|---|---|
| **Consulta observada** | Família de consulta identificada pela telemetria ou por solicitação humana. | Diagnóstico, plano ou recomendação. |
| **Consulta candidata** | Consulta priorizada porque há indício de impacto material, regressão ou custo crônico. | Toda consulta observada. |
| **Caso de análise** | Investigação auditável de uma consulta candidata em um contexto específico. | A própria consulta. |
| **Evidência** | Fato de fonte verificável: métrica, plano, índice, estatística ou metadado. | Opinião de LLM ou conclusão não sustentada. |
| **Contexto sanitizado** | Versão autorizada de dados para sair da fronteira confiável ou seguir ao modelo. | Garantia de que qualquer SQL é seguro. |
| **Finding** | Sinal técnico específico, limitado e vinculado a evidências. | Recomendação pronta. |
| **Diagnóstico** | Interpretação estruturada de findings sustentados por evidências. | Certeza absoluta ou mudança proposta. |
| **Hipótese de otimização** | Mudança possível que pode mitigar uma causa. | Mudança comprovadamente benéfica. |
| **Validação** | Experimento controlado que testa a hipótese e registra limitações. | Aplicação em produção. |
| **Recomendação** | Hipótese validada apresentada com benefício, riscos e limitações. | Ordem automática de alteração. |
| **Decisão humana** | Aprovação, rejeição ou pedido de alteração, com ator e justificativa. | Resultado do modelo. |
| **Auditoria** | Histórico imutável de fatos e decisões para reconstruir o caso. | Log textual aleatório. |

Exemplo da cadeia de raciocínio:

- **Finding:** a consulta lê uma relação grande sequencialmente e retorna uma fração pequena das linhas.
- **Hipótese:** um índice em `customer_id` pode reduzir leituras para esse padrão de filtro.
- **Validação:** no ambiente de teste, a hipótese altera o plano e reduz as métricas definidas, dentro das limitações registradas.
- **Recomendação:** criar o índice é uma alternativa justificável, com custo de escrita e risco descritos.
- **Decisão humana:** o DBA aprova, rejeita ou pede uma alternativa por motivo explícito.

## 4. Quando uma consulta vira candidata

Uma consulta não se torna candidata por um único sintoma. Ela deve apresentar impacto técnico material e contexto suficiente para investigação.

Sinais relevantes incluem:

- participação relevante no tempo total de execução na janela observada;
- alto volume de blocos lidos, escrita temporária ou pressão de I/O;
- latência acima de um SLO configurado;
- degradação em relação ao comportamento anterior;
- mudança de plano acompanhada de piora de métricas;
- custo crônico e recorrente relevante, ainda que não tenha ocorrido regressão;
- aumento de chamadas **combinado** com custo total relevante;
- saturação de CPU, I/O, conexões ou waits no contexto do banco.

O Vigil pode abrir um caso por impacto absoluto mesmo sem histórico. O sistema deve explicar qual evidência abriu o caso e sua confiança histórica.

### Observabilidade progressiva

| Camada | Funcionamento | Confiança |
|---|---|---|
| **Impacto absoluto** | Prioriza a carga relevante na janela atual; funciona desde a primeira coleta. | `ABSENT` |
| **Baseline provisório** | Compara com períodos recentes usando estatísticas móveis. | `PROVISIONAL` |
| **Baseline sazonal** | Compara períodos equivalentes, como hora e dia da semana. | `MATURE` |

O baseline é por família de consulta e por target. Um pico conhecido não é automaticamente uma anomalia; pode ser registrado como janela operacional esperada. Ainda assim, se causar saturação, pode revelar problema de capacidade ou arquitetura.

## 5. Estados do caso de análise

`AnalysisCase` é a investigação completa e auditável. Ele é o aggregate root do domínio: protege as transições e invariantes do fluxo.

| Estado | Significado | Condição mínima |
|---|---|---|
| `DISCOVERED` | A consulta foi identificada ou recebida para análise. | Consulta observada e identificador do caso. |
| `TRIAGED` | A consulta foi selecionada para investigação. | Razão de priorização explícita. |
| `EVIDENCE_COLLECTED` | Fatos técnicos foram coletados. | Ao menos uma evidência vinculada ao caso. |
| `SANITIZED` | O contexto foi avaliado pela política de privacidade. | Resultado e versão da política registrados. |
| `DIAGNOSED` | Findings e diagnóstico foram produzidos. | Referências às evidências e limitações. |
| `HYPOTHESIS_PROPOSED` | Há uma otimização possível a testar. | Hipótese ligada a diagnóstico, riscos e pré-condições. |
| `VALIDATING` | Experimento isolado está preparado ou em execução. | Ambiente e hipótese definidos. |
| `VALIDATED` | A hipótese mostrou benefício suficiente no ambiente utilizado. | Comparação, fidelidade e limites registrados. |
| `INVALIDATED` | A hipótese não ajudou ou piorou o cenário. | Evidência comparativa. |
| `INCONCLUSIVE` | Não foi possível concluir com segurança. | Limitação explícita. |
| `AWAITING_HUMAN` | Recomendação validada está pronta para revisão. | Evidências, riscos e limitações disponíveis. |
| `APPROVED` | Um humano aprovou a recomendação. | Ator, data e justificativa. |
| `REJECTED` | Um humano rejeitou a recomendação. | Ator, data e justificativa. |
| `CHANGES_REQUESTED` | O revisor pediu alteração. | Pedido explícito. |
| `ARCHIVED` | O caso foi encerrado e preservado. | Não recebe mudanças silenciosas. |

Falhas operacionais, como `COLLECTION_FAILED`, `MODEL_FAILED`, `VALIDATION_FAILED` e `TIMED_OUT`, devem ser registradas como eventos com causa e podem levar o caso a um estado terminal ou de reprocessamento conforme a política. Não devem apagar evidências já coletadas.

### Invariantes

- Não existe diagnóstico antes de um contexto autorizado pela política de sanitização.
- Hipótese exige diagnóstico.
- Recomendação exige validação suficiente; `INCONCLUSIVE` não é evidência de benefício.
- Decisão humana exige ator e justificativa.
- Eventos de auditoria não carregam SQL bruto nem valores sensíveis.
- O Vigil não executa alteração no banco monitorado, inclusive após `APPROVED`.

## 6. Objetos fundamentais

### ObservedQuery

Representa a família de consulta identificada. Contém, de forma típica:

- fingerprint do Vigil;
- identificador nativo da fonte, quando existir;
- origem (`POSTGRESQL`, integração de aplicação ou fixture);
- momento de observação;
- ambiente;
- referência segura à representação permitida da consulta.

Ele não deve persistir SQL bruto por padrão.

### Fingerprint, template normalizado e SQL bruto

Estes são conceitos diferentes:

| Representação | Uso | Exemplo |
|---|---|---|
| SQL bruto | Existe temporariamente em uma fonte autorizada. | `SELECT * FROM users WHERE cpf = '099...'` |
| Template normalizado | Explica a estrutura sem valores literais. Ainda pode revelar termos de negócio. | `SELECT * FROM users WHERE cpf = $1` |
| Contexto sanitizado/pseudonimizado | Pode ser usado fora da fronteira confiável, conforme política. | `SELECT * FROM entity_1 WHERE field_1 = $1` |
| Fingerprint | Identificador opaco para agrupamento e correlação. | `hmac-sha256:84fa9c1d...` (64 hex) |

Muitas execuções concretas podem compartilhar a mesma estrutura normalizada e, portanto, o mesmo fingerprint.

```text
SQL autorizado
  ↓
normalização (forma canônica v1, sem literais)
  ↓
HMAC-SHA256(chave estável, "v1\n" + forma canônica)
  ↓
fingerprint versionado  (value = "hmac-sha256:" + hex; algorithmVersion = "v1")
```

Fingerprint não é SQL criptografado e não permite reconstruir SQL. O Vigil usa **HMAC** e não um SHA-256 simples: um hash puro de uma consulta conhecida é vulnerável a ataque de dicionário (quem adivinha a consulta confirma o hash); a chave torna o token verificável mas não força-brutável. A chave é **estável por instalação**, vinda de configuração (`VIGIL_FINGERPRINT_KEY`), nunca hardcoded — é o que permite correlacionar a mesma família de consulta entre coletas ao longo do tempo. Ela é diferente da chave de pseudonimização do `SchemaBlueprint`, que é **efêmera por coleta**; as duas nunca se misturam. Sem chave configurada, o cálculo falha de forma explícita (fail-closed); apenas o perfil de demonstração declara uma chave de demo fixa no seu arquivo de configuração.

#### Regras de normalização — forma canônica `v1`

A porta `QueryFingerprinter` recebe o SQL e devolve um resultado explícito: sucesso (`QueryFingerprint`) ou **não suportado** com motivo — nunca exceção genérica, nunca hash do SQL bruto como fallback. A forma canônica `v1`:

- Remove comentários `-- ...` e `/* ... */`, inclusive aninhados (como no PostgreSQL).
- Substitui **toda** constante por placeholder posicional `$1, $2, ...` na ordem de aparição: números (inteiro, decimal, notação científica); strings `'...'` com `''` escapado; `E'...'`; dollar-quote `$$...$$` e `$tag$...$tag$`; `TRUE`/`FALSE`; e o valor de literais tipados (`DATE '2024-01-01'` → `DATE $n`). `NULL` permanece como palavra.
- Placeholders já presentes no texto (`$1, $2...`) são renumerados na mesma sequência.
- Uma lista composta **somente** de constantes em `IN (...)` colapsa para um único placeholder: `IN (1,2,3)` e `IN (7)` → `IN ($n)`. Decisão deliberada: a aridade de uma lista `IN` de constantes é tratada como literal irrelevante, como os próprios valores; uma lista com qualquer não-constante (coluna, subquery) é preservada.
- `LIMIT`/`OFFSET` com número seguem a regra geral e viram placeholder.
- Palavras-chave em maiúsculas; identificadores sem aspas em minúsculas (case folding do PostgreSQL); identificadores entre aspas duplas preservam o case exato (e as aspas), então `"Orders"` difere de `orders`.
- Espaços em branco colapsam em um único espaço e o `;` final é removido.
- **Fail-closed:** mais de uma instrução, string/comentário/dollar-quote não terminado, instrução vazia ou qualquer token não reconhecido → "não suportado" com motivo.

O SQL bruto e a forma canônica nunca são persistidos nem registrados em log; apenas o fingerprint é.

O `queryid` de `pg_stat_statements` pode ser usado como identificador da fonte PostgreSQL, contextualizado por banco, usuário e tipo de execução. Ele **não** substitui o fingerprint nem é o identificador global e permanente do Vigil: depende do PostgreSQL, pode mudar em recriações de objetos e não deve ser presumido estável entre versões maiores.

### Sanitização fail-closed (`SanitizationResult`)

Antes de produzir qualquer contexto seguro, o SQL autorizado passa pela porta `QuerySanitizer`, que devolve um resultado explícito `SanitizationResult`, com exatamente dois casos:

- **`Safe`** — o conteúdo sanitizado (sem literais) e a lista de regras aplicadas (`COMMENTS_REMOVED`, `LITERALS_PLACEHOLDERED`, `WHITESPACE_COLLAPSED`).
- **`Blocked`** — o motivo como enum (`SanitizationBlockedReason`) e um detalhe curto **derivado do próprio enum**, nunca texto livre. Por construção, o detalhe não contém o SQL original nem qualquer literal.

A porta **nunca lança exceção** para conteúdo recusado: recusa é um `Blocked`. Qualquer situação imprevista (token desconhecido, erro interno, entrada nula) também é `Blocked` — jamais `Safe` nem repasse do texto bruto (fail-closed).

A implementação **reutiliza** dois componentes já existentes, sem duplicar lógica:

1. a política do guard de EXPLAIN (`PostgresSelectStatementGuard`): instrução única, apenas `SELECT`, cláusulas proibidas e tamanho máximo. Uma recusa vira `Blocked` com o motivo correspondente.
2. o normalizador do fingerprint (`SqlCanonicalizer`): remove comentários e troca todos os literais por placeholders; "não suportado" vira `Blocked`.

**Diferença deliberada em relação ao guard de EXPLAIN:** o sanitizador aceita placeholders já presentes (`$1`), porque o texto vindo de `pg_stat_statements` já é normalizado; o guard de EXPLAIN continua rejeitando placeholders, pois não há binds para planejar.

Integração com o caso: a transição `AnalysisCase.sanitize(...)` só é alcançável com um `SanitizationResult.Safe` (garantia de tipo). Com `Blocked`, o caso **não avança** — a partir de `EVIDENCE_COLLECTED` não há transição terminal de rejeição com motivo, então o caso permanece no estado atual, o caso de uso devolve o `Blocked` ao chamador e registra apenas o enum (sem SQL) no log de aplicação. A semântica de `CaseEvent` e de `reconstitute()` permanece inalterada.

### Seleção de candidatas por impacto absoluto (RF09)

A seleção de consultas candidatas por **impacto absoluto** é determinística e vive no domínio (`ImpactSelectionPolicy`), sem dependência de infraestrutura. O fluxo é `consulta observada → Candidate → AnalysisCase`.

- **Impacto é tempo total de execução**, nunca número de chamadas. A política ordena as famílias pelo delta de tempo total no intervalo.
- Uma família só é **elegível** se cruzar **dois** limiares: participação mínima no tempo do workspace (*share*) **e** tempo total mínimo no intervalo. Limiares padrão documentados: **share ≥ 5%**, **tempo ≥ 100 ms**, **top N = 5** (configuráveis).
- **Número de chamadas isolado nunca torna uma família elegível** (critério 6): alto RPM com baixo tempo total não abre caso.
- Desempate **estável**: tempo total desc, chamadas desc, fingerprint asc. A mesma entrada sempre gera a mesma saída. Resultado vazio é válido e vem com motivo (`NO_CANDIDATES_ABOVE_THRESHOLDS` ou `WARMING`).
- Uma `Candidate` representa a **decisão de investigar**, não a confirmação de que a consulta é inadequada: impacto precede diagnóstico. No escopo atual não há baseline, então `BaselineConfidence.ABSENT` — e uma família de alto impacto pode gerar candidata mesmo assim (critério 2).

**Agregação por família:** o calculador expõe deltas por chave nativa `(dbid, userid, queryid, toplevel)` sem alterar o delta agregado do workspace. As chaves são agrupadas por **fingerprint** (a família, RF11), calculado a partir do texto normalizado do `pg_stat_statements` **dentro do adapter**; o `queryid` é só a origem (`SourceQueryId`). Chaves cujo SQL não pode ser identificado (`<insufficient privilege>`, vazio ou não suportado pelo normalizador) ficam **fora do ranking** e entram num contador de não classificadas (fail-closed); o SQL bruto nunca é identidade nem aparece no motivo ou em log. Estado de *warming* (reset de estatísticas, dealloc, tempo não monotônico ou contador regredindo) resulta em seleção vazia com motivo `WARMING`.

**Abrir caso:** o `SelectionReason` (código + números, texto gerado, sem SQL) vira o **motivo sugerido da triagem** (`CaseEvent.reason` em `DISCOVERED→TRIAGED`); o operador confirma ou edita. A abertura continua sendo decisão humana e a idempotência permanece pela `discovery_idempotency_key`, não pelo fingerprint. Regressão contra baseline continua **fora do escopo** (modelada, não implementada).

### Evidence

É um fato verificável. Exemplos: plano de execução, métricas por janela, relação consultada, índices existentes e estatísticas do planner.

Toda evidência deve registrar origem, instante de captura, versão do coletor, escopo, nível de confiança e conteúdo sanitizado ou referência segura.

### Finding e Diagnosis

Findings possíveis:

- `POSSIBLE_AVOIDABLE_SEQ_SCAN`;
- `POSSIBLE_MISSING_INDEX`;
- `CARDINALITY_ESTIMATION_DIVERGENCE`;
- `NON_SARGABLE_PREDICATE`;
- `EXPENSIVE_JOIN`;
- `EXCESSIVE_TEMP_IO`;
- `PLAN_REGRESSION`.

Um finding explica observação, gravidade, evidências e limites. O diagnóstico interpreta um conjunto de findings; pode declarar incerteza e nunca deve mascarar suposições como fatos.

### OptimizationHypothesis, ValidationRun e Recommendation

Uma hipótese declara o problema tratado, a mudança proposta, benefício esperado, riscos, pré-condições e evidências usadas. Uma hipótese pode inclusive concluir que nenhuma mudança é justificável com a evidência disponível.

`ValidationRun` registra ambiente, plano antes/depois, métricas comparadas, resultado, limitações e nível de fidelidade.

Uma recomendação agrega diagnóstico, hipótese, validação, benefício observado/estimado, riscos, limitações e instrução de revisão humana.

## 7. Telemetria e termos de PostgreSQL

### Snapshot de telemetria

Snapshot não é backup do banco nem cópia de tabelas. É uma medição compacta, por janela, de contadores agregados.

O Vigil coleta dados amplos o suficiente para ranquear impacto no workload, mas só abre planos e diagnósticos detalhados para candidatas. Snapshots detalhados têm retenção limitada; dados antigos viram baselines estatísticos compactos.

`pg_stat_statements` é a fonte inicial de observação. Entre suas métricas estão chamadas, tempo total e médio, linhas, blocos compartilhados e blocos temporários. Como seus contadores são acumulados, o Vigil calcula deltas entre coletas para obter a janela atual.

### Plano e custo

- **Plano de execução:** estratégia escolhida pelo PostgreSQL, como `Seq Scan`, `Index Scan`, `Bitmap Heap Scan`, `Nested Loop`, `Hash Join`, `Merge Join`, `Sort` e `Aggregate`.
- **`EXPLAIN`:** mostra o plano estimado sem executar a consulta; é o padrão seguro para o ambiente monitorado.
- **`EXPLAIN ANALYZE`:** executa a consulta e mede resultado real; deve ser usado somente em sandbox, réplica governada ou situação explicitamente autorizada.
- **Custo estimado:** unidade interna para comparar alternativas do planner, não milissegundos nem custo financeiro de cloud.
- **Cardinalidade:** quantidade de linhas estimada ou real em uma etapa do plano.
- **Seletividade:** proporção de linhas que um filtro mantém. Um filtro que retorna 10 linhas em uma tabela de 1 milhão é altamente seletivo.

### Sequential Scan e índices

`Sequential Scan` lê a relação sequencialmente. Pode ser a melhor escolha para relações pequenas ou para consultas que retornam parcela grande dos dados. Ele só se torna suspeito quando combinado, por exemplo, com relação grande, baixa fração retornada, alto I/O, alta frequência ou regressão de plano.

Índices podem melhorar leituras, mas possuem custo de armazenamento e manutenção em `INSERT`, `UPDATE` e `DELETE`. O Vigil precisa considerar seletividade, índices existentes, ordem de colunas, tamanho da relação e padrão de escrita antes de sugerir um.

Um predicado sargável costuma permitir melhor uso de índice. Exemplo geralmente favorável:

```sql
WHERE created_at >= :start_date
```

Exemplo frequentemente menos favorável para índice comum:

```sql
WHERE DATE(created_at) = :date
```

### Hipóteses de índice e sandbox

HypoPG pode apoiar a avaliação de índices hipotéticos, sem criar o índice físico, mas o resultado ainda é uma estimativa do planner. Ele não substitui uma medição real em ambiente controlado.

Níveis de evidência de validação:

| Nível | Ambiente | Tipo de conclusão permitido |
|---|---|---|
| S0 | Parser/AST e regras estáticas | Viabilidade sintática/lógica, não desempenho. |
| S1 | PostgreSQL com schema | Compatibilidade básica de plano. |
| S2 | Schema e dados sintéticos | Tendência técnica inicial. |
| S3 | Dados sintéticos calibrados ou autorizados | Evidência comparativa mais forte. |
| S4 | Staging ou réplica governada | Evidência próxima de produção, ainda com limitações. |

Nenhum nível deve alegar reproduzir perfeitamente produção sem evidência correspondente.

## 8. Arquitetura

### Domínio, aplicação, portas e adaptadores

- **Domínio:** regras independentes de tecnologia, como “diagnóstico exige contexto autorizado”.
- **Camada de aplicação:** coordena casos de uso, persistência e chamadas a portas; não contém invariantes de negócio.
- **Porta:** interface que representa uma necessidade do core.
- **Adaptador:** implementação concreta de uma porta.

| Porta | Adaptadores possíveis |
|---|---|
| `AnalysisCaseRepository` | Memória, SQLite, PostgreSQL futuro |
| `TelemetrySource` | JDBC PostgreSQL, fixture de teste |
| `SqlSanitizer` | Implementação local conforme política |
| `InferencePort` | Bedrock, Ollama, vLLM, Azure AI Foundry |
| `HypothesisValidator` | HypoPG, Testcontainers, staging governado |
| `Clock` | Relógio real, relógio fixo de teste |
| `IdGenerator` | UUID em produção, implementação determinística em teste |

Hibernate não é requisito para acessar a telemetria do PostgreSQL; JDBC é a integração natural do coletor. Hibernate pode futuramente ser uma origem complementar de observação em aplicações Java.

### Persistência

SQLite é a persistência padrão da instalação local e de target único. Deve usar volume persistente e WAL. Ele é adequado a um coletor e processo escritor; não deve ser compartilhado entre hosts ou usado como banco central para várias réplicas.

Uma evolução para PostgreSQL como adapter de persistência só é necessária se o produto passar a exigir múltiplas instâncias ativas, alta disponibilidade, centralização ou retenção muito maior.

### Idempotência e eventos

Idempotência significa que repetir uma operação não cria efeitos indevidos. Uma evidência enviada duas vezes não deve criar recomendações ou eventos duplicados.

Evento de domínio é um fato passado, como `EvidenceCollected` ou `ValidationCompleted`. Ele não é um comando. Exemplo:

```text
Comando: “Sanitize esta consulta.”
Evento:  “A consulta foi sanitizada.”
```

## 9. IA no Vigil

LLM é um componente auxiliar para interpretar contexto permitido e gerar hipóteses estruturadas. Ele não substitui métricas, plano, regras determinísticas ou decisão humana.

A saída deve obedecer a schema validável, contendo no mínimo: tipo de problema, evidências referenciadas, hipótese, confiança e limitações. Texto livre como “talvez crie um índice” não é suficiente.

**Grounding** é a exigência de que afirmações relevantes possam ser associadas a evidência ou regra. Para reduzir alucinações, o Vigil usa contexto minimizado e sanitizado, regras determinísticas, schema de saída, validação e curadoria humana.

Um “agente” não precisa ser autônomo: inicialmente pode ser um serviço Java com responsabilidade única e contrato claro. Frameworks de agentes só entram se trouxerem benefício comprovado.

## 10. Segurança e governança

- **Sanitização:** remove ou generaliza valores sensíveis, comentários, parâmetros e identificadores definidos pela política. Identificadores de negócio também podem ser sensíveis.
- **Fail-closed:** se não for possível produzir contexto seguro para egress ou inferência, a operação é bloqueada; nunca se envia “mesmo assim”.
- **Menor privilégio:** a credencial PostgreSQL do Vigil lê apenas métricas e catálogo necessários e executa somente comandos permitidos. Não cria índices nem altera dados.
- **Egress de dados:** em modo local, não há saída de conteúdo sensível. Com inferência gerenciada, o sistema minimiza o contexto enviado e respeita região, identidade, rede e política do provedor.
- **Prompt injection:** SQL, comentários e identificadores devem ser tratados como dados não confiáveis, nunca como instruções para o modelo.
- **Human-in-the-loop:** é obrigatório antes da aprovação; aprovação nunca dispara alteração automática.

O relatório padrão mostra template normalizado ou sanitizado. A consulta original, se a política permitir, só pode ser resolvida em visão privilegiada, auditada e a partir de uma referência segura na fonte. O `pg_stat_statements` agrupa famílias de query e não preserva necessariamente uma amostra exata de cada execução.

## 11. Pesquisa e avaliação

### Casos de teste e fixtures

Cada caso de teste deve informar:

- SQL permitido para o experimento;
- schema e dados sintéticos;
- índices existentes;
- plano e problema esperados;
- hipóteses aceitáveis e proibidas;
- resultado esperado de validação;
- versão do PostgreSQL, configuração e seed quando aplicável.

Uma fixture é um conjunto fixo e reproduzível de schema, dados e cenário. Um benchmark organiza casos e métricas para comparar abordagens.

### Métricas de avaliação

- **Precisão de findings:** dos problemas sinalizados, quantos eram problemas reais?
- **Recall de findings:** dos problemas existentes, quantos foram identificados?
- **Taxa de recomendação insegura:** quantas recomendações deveriam ter sido bloqueadas, rejeitadas ou marcadas inconclusivas?
- **Qualidade da hipótese:** a proposta é tecnicamente plausível e sustentada?
- **Qualidade de explicação:** as evidências e limitações são compreensíveis para curadoria?
- **Reprodutibilidade:** outro avaliador consegue repetir o experimento com versões, dados, prompts e configurações declarados?

Não confundir **baseline operacional** — comportamento histórico de uma consulta — com **grupos de comparação do experimento**. Para avaliar o TCC, os grupos podem ser:

| Grupo | Abordagem |
|---|---|
| B0 | Triagem manual baseada em métricas |
| B1 | Regras determinísticas |
| B2 | LLM sem validação |
| B3 | Vigil híbrido: regras, LLM e validação |

## 12. Decisões e referências relacionadas

- ADR-001 — Observabilidade progressiva e baseline adaptativo.
- ADR-002 — SQLite como persistência padrão da instalação local do Vigil.
- ADR-007 — Regras diagnósticas e evolução aditiva de Evidence.
- ADR-008 — Fronteira segura e validada de inferência.
- [PostgreSQL — pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)
- [PostgreSQL — EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html)
- [PostgreSQL — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [SQLite — Write-Ahead Logging](https://www.sqlite.org/wal.html)

## 13. Contrato executável de diagnóstico e curadoria

O diagnóstico de produção é determinístico antes de qualquer inferência. `DiagnosticRuleEngine` cruza nós do plano, metadados pseudonimizados e impacto observado. A regra inicial `SEQ_SCAN_SELECTIVE_FILTER` só produz um `Finding` quando todas as pré-condições são comprovadas; ausência ou ambiguidade vira inconclusão explícita.

O encadeamento executável é:

```text
Evidence do plano + Evidence de catálogo + impacto
    → Finding
    → Diagnosis
    → OptimizationHypothesis
    → ValidationOutcome + FidelityLevel
    → Recommendation versionada
    → HumanDecision vinculada à versão
```

`Recommendation` exige hipótese, resultado, evidências, riscos e limitações. A decisão humana informa a versão vigente para impedir aprovação de material obsoleto. O nível de fidelidade descreve o ambiente da validação e não transforma resultado sintético em medição do target.

A fronteira de inferência recebe apenas `SanitizedInferenceContext`, schema, versão de template e timeout. O adaptador não recebe prompt livre. Toda resposta passa por `InferenceOutputValidator`, que rejeita referências inexistentes, schema incompatível e conteúdo fora do contrato antes de qualquer mudança no caso.
