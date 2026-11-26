# Linguagem Ubíqua

## Status

Proposto

## Objetivo

Definir os termos canônicos usados no código, documentação, testes, interface e TCC do sistema Vigil.

O objetivo é evitar ambiguidades como chamar uma consulta observada de "problema", chamar uma hopótese de "recomendação validada" ou tratar uma consulta sanitizada como se fosse o SQL original.

## Princípios de Linguagem

- Uma consulta observada não é automaticamente uma consulta problemática
- Uma candidata não é automaticamente um caso de análise.
- Uma evidência é um fato observado ou coletado; NÃO É UMA OPINIÃO.
- Um diagnóstico é uma conclusão sustentada por evidências.
- Uma hipótese é uma proposta testável; não é uma mudança aplicada.
- Uma validação favorável em sandbox não é uma garantia de benefício em produção.
- Uma aprovação humana não autoriza o Vigil a alterar o target monitorado.
- Materialized Views são tratadas como relações do banco, assim como tabelas.
- O Vigil analisa um único target por instalação no MVP.

## Contextos delimitados

| Contexto                 | Responsabilidade                                                                 |
|--------------------------|----------------------------------------------------------------------------------|
| Observabilidade          | Coletar telemetria, snapshots, deltas e sinais de impacto.                       |
| Gestão de Casos          | Criar, evoluir e auditar AnalysisCases.                                          |
| Diagnóstico e Otimização | Interpretar evidências e propor hipóteses de melhoria.                           |
| Validação                | Avaliar hipóteses somente em sandbox isolado.                                    |
| Curadoria                | Permitir que uma pessoa aprove, rejeite ou solicite revisão de uma recomendação. |

## Termos Canônicos

### Target

Instância ou cluster de banco de dados monitorado por uma instalação do Vigil.
No MVP, uma instalação do Vigil possui exatamente 1 target.

Exemplos:
- um cluster Aurora PostgreSQL.
- uma instância PostgreSQL local usada em laboratório.
- um PostgreSQL executado por Testcontainers.

Target não é sinônimo de tabela, schema, consulta ou ambiente.

### TargetProfile

Configuração pública que identifica e define limites operacionais do target.
Pode conter host, porta, database, ambiente, modo SSL, timeouts e capacidades.
Não deve conter senha, token ou segredo em um objeto que possa aparecer em logs.

### Relation

Objeto relacional consultável pelo banco. No PostgreSQL, pode representar tabela, Materialized View, tabela particionada ou outro tipo de relação suportado pelo Vigil.

Uma Materialized View não recebe tratamento especial no domínio: ela é uma Relation com tipo próprio.

### RelationMetadata

Metadados facturais sobre uma Relation coletados do target.

Exemplos:
- nome ou identificador sanitizado da relação;
- tipo da relação;
- quantidade estimada de linhas;
- colunas relevantes;
- índices existentes;
- estatísticas disponíveis.

### RelationType

Tipo da Relation: `TABLE`, `MATERIALIZED_VIEW`, `PARTITIONED_TABLE` ou `FOREIGN_TABLE` (`relkind` `r`, `m`, `p`, `f`).
Views comuns e demais `relkind` não são coletados.

### SchemaBlueprint

Parte relevante do schema do target para um caso, coletada somente dos catálogos em um instante e com uma source
(`POSTGRESQL_CATALOG`): relações, colunas (tipo SQL e nulabilidade), constraints (tipo e colunas) e índices (método,
chaves, `unique`, `primary`, `partial`, `valid`).

- Todo nome de schema, relação, coluna e índice é um pseudônimo (HMAC com sal por coleta), estável apenas dentro do
  blueprint. Nomes de tipos fora de `pg_catalog` saem como `user_defined`.
- `reltuples` e `relpages` são **estimativas do planner**, nunca contagem exata; `reltuples = -1` (nunca analisada) é
  desconhecido, não zero.
- O texto do predicado de um índice parcial, a definição do índice e a expressão de um CHECK não são carregados: um
  índice parcial só é marcado `partial`, e uma chave por expressão é só uma chave sem coluna.
- Um blueprint com `incompleteness` não vazia (`CATALOG_PRIVILEGE_DENIED`, `RELATION_NOT_VISIBLE`) é explicitamente
  incompleto e não significa "não existe".

### RawSql

Texto original da consulta obtido dentro da fronteira confiável do target.

RawSql pode conter valores pessoais, identificadores de negócio ou regras sensíveis. Por isso:

- não é persistido pelo core por padrão;
- não aparece em logs;
- não é enviado a modelos de inferência;
- não é incluído em datasets de avaliação;
- pode existir temporariamente apenas na etapa autorizada de captura e sanitização.

### NormalizedQueryTemplate

Representação estrutural do SQL na qual literais são substituídos por parâmetros tipados ou posicionais.

Exemplo:

```sql
SELECT * FROM usuarios WHERE cpf = '099.888.765-08'

-- pode originar:
                       
SELECT * FROM usuarios WHERE cpf = <parameter_1:string> 
```
A normalização remove valores literais, mas não garante que nomes de tabelas, colunas ou regras de negócio deixem de ser sensíveis.

NormalizedQueryTemplate não é automaticamente seguro para armazenamento, logs ou inferência externa.

### QueryFingerprint

Identificador estável e versionado de uma família de consultas estruturalmente equivalentes.

O fingerprint é derivado de uma representação normalizada, mas é tratado pelo core como um identificador opaco.
Ele é usado para agrupar telemetria e identificar uma família de consultas no mesmo target.

Propriedades esperadas:
- estabilidade para consultas equivalentes;
- versão explícita do algoritmo;
- ausência de valores literais;
- escopo associado ao target e à fonte de telemetria;
- não deve ser usado como mecanismo de desserialização do SQL original.

Um fingerprint não é uma garantia absoluta de anonimização. Ele não autoriza, por si só, o envio de contexto a serviços externos.

### QueryFamily

Agrupamento lógico de execuções que compartilham o mesmo QueryFingerprint no mesmo target e na mesma fonte de telemetria.

Uma QueryFamily pode ser observada em muitas janelas de tempo sem que exista um caso de análise.

### ObservedQuery

Representação da QueryFamily tal como foi observada pelo Vigil em uma janela de telemetria.

ObservedQuery descreve o que ocorreu; ela não afirma que há um problema.

Ela pode referenciar:
- QueryFingerprint;
- fonte de telemetria;
- tipo do comando;
- janela observada;
- métricas agregadas;
- relações envolvidas, quando disponíveis;

### TelemetrySnapshot

Registro de métricas acumuladas ou instantâneas coletadas em um instante.

No PostgreSQL, `pg_stat_statements` oferece contadores acumulados. Portanto, um snapshot não representa sozinho o comportamento recente de uma consulta.

No modelo executável, o snapshot guarda o instante, o marco opcional de `stats_reset`, o contador global de `dealloc`,
a visibilidade da coleta e uma lista imutável de `QueryCounters`. A identidade de cada série é
`(dbid, userid, queryId, topLevel)`; `queryId` pode ter sinal. O snapshot não contém SQL.

### QueryWindowMetrics

Resultado puro da comparação entre dois snapshots: início exclusivo e fim inclusivo. Contadores de chamadas, linhas
e blocos são deltas inteiros não negativos; tempo total permanece em `BigDecimal` de milissegundos. A média por
execução é `tempo total delta / chamadas delta`, com seis casas decimais e `HALF_EVEN`, somente se houver chamadas.

`NO_BASELINE` (primeira coleta), `ENTRY_NEW` (identidade nova), `ENTRY_MISSING` (identidade removida),
`ENTRY_ABSENT` (identidade ausente nos dois extremos), `RESET`,
`EVICTION`, `INCOMPLETE` e `INVALID_WINDOW` não possuem delta. Uma janela `COMPLETE` com delta zero é válida e
diferente da ausência de baseline. Crescimento de `dealloc` invalida conservadoramente a janela inteira porque a
fonte não identifica qual entrada foi desalocada; regressão de contador ou mudança em `stats_reset` indica reset.

### ObservationWindow

Intervalo calculado a partir de snapshots compatíveis.

A janela contém os deltas necessários para interpretar comportamento recente, como aumento de chamadas, tempo total, linhas, blocos lidos e blocos gravados.

ObservationWindow não é um backup do banco nem uma cópia de tabelas.

### ImpactAssessment

Avaliação determinística do impacto atual de uma QueryFamily ou ObservedQuery

Ela combina sinais como:
- participação no tempo total do workload;
- I/O;
- Latência;
- Frequência;
- Volume mínimo de chamadas;
- Comportamento na janela observada.

Sequential Scan isolado, RPM isolado ou uma consulta lenta isolada não caracterizam impacto suficiente por si só.

### BaselineConfidence

Nível de confiança disponível para comparar o comportamento atual de uma `QueryFamily` com seu histórico.

Baseline não é uma condição para o Vigil funcionar. Na ausência de histórico, o Vigil usa impacto absoluto. Baseline serve para detectar desvio de comportamento.

### Contrapartida em código (RF09)

Estes conceitos agora têm tipos Java no domínio (`com.vigil.core.domain`), sem dependência de infraestrutura:

| Conceito | Tipo | Observação |
|---|---|---|
| ImpactAssessment | `ImpactAssessment` | deltas da família (`QueryImpactDelta`, com `share` 0..1), posição no ranking e `BaselineConfidence`. |
| BaselineConfidence | `BaselineConfidence` | `ABSENT`, `ESTABLISHING`, `ESTABLISHED`; no escopo atual sempre `ABSENT` (sem baseline; regressão fora do escopo). |
| Candidate | `Candidate` | `QueryFingerprint` (identidade da família, RF11), `List<SourceQueryId>` (origens), `ImpactAssessment` e `SelectionReason`. |
| Política de seleção | `ImpactSelectionPolicy` | determinística e pura: ordena por tempo total de execução (impacto absoluto), aplica limiares de share e tempo, nunca seleciona por número de chamadas isolado. |
| Motivo | `SelectionReason` | código + números; o texto é gerado e nunca contém SQL nem literais. |

O fluxo é `consulta observada → Candidate → AnalysisCase`: o use case `SelectCandidatesByImpactUseCase` produz as `Candidate` a partir de dois snapshots; abrir caso usa `DiscoverObservedQueryUseCase`, e o texto do `SelectionReason` é o motivo sugerido da triagem (`CaseEvent.reason`).

A classificação depende de uma `BaselinePolicy` versionada e auditável. Os valores abaio são a política inicial do Vigil, não verdades universais.

#### ABSENT

Não existe histórico suficiente ou confiável para comparação.

Condições:
- Menos de 24 horas entre a primeira e a última `ObservationWindow` válida;
- Menos de 48 janelas válidas para a `QueryFamily`;
- Cobertura menor que 80% das janelas esperadas;
- Reset detectado em `pg_stat_statements`;
- Mudança incompatível na versão do fingerprint, target ou política de coleta.

Neste estado, o Vigil pode abrir um `AnalysisCase` por impacto absoluto, mas não deve afirmar que houve regressão histórica.

#### PROVISIONAL

Existe histórico recente suficiente para uma comparação não sazonal.

Condições:
- Pelo menos 24 horas de histórico válido;
- Pelo menos 48 `ObservationWindows` válidas;
- Cobertura mínima de 80% no período;
- Ausência de reset de contador dentro da janela analisada;
- Cada janela usada respeita o volume mínimo de chamadas definido pela política.

O baseline provisório usa métricas agregadas recentes, como média móvel, mediana e percentis calculados sobre janelas válidas.

Ele permite afirmar:

> O comportamento atual diverge do comportamento recente observado.

Ele não permite afirmar:

> O comporamento atual é anormal para este mesmo dia e horário.

#### Mature

Existe histórico suficiente para comparação sazonal simples.

Condições:
- Pelo menos 56 dias de histórico agregado válido;
- Pelo menos oito observações comparáveis para cada bucket sazonal utilizado;
- Cobertura mínima de 80%;
- Ausência de reset ou alteração de configuraçòa que invalide a série;
- Retenção de métricas agregadas suficiente para reconstruir a comparação.

No modelo inicial, um bucket sazonal pode ser definido por:
- dia da semanal;
- hora do dia;
- QueryFingerprint;
- TargetProfile.

Exemplo: uma consulta observada às segundas-feiras entre 14h e 15h, é comparada com pelo menos oito segundas-feiras anteriores no mesmo intervalo.

MATURE permite afirmar:

> O comportamento atual diverge do padrào histórico comparável.

#### Retenção

A retenção é diferente da confiança do baseline.
- Snapshots detalhados: retenção inicial de 14 dias.
- Janelas e métricas agregadas para baseline: retenção inicial de 90 dias.
- Eventos, casos, diagnósiticos e resultados de validação: retenção duradoura, conforme política da instalação.

A retenção de 90 dias permite sustentar o requisito de 56 dias dos baseline MATURE e preserva margem para auditoria e comparação.

#### Escopo do MVP

No MVP:
- `ABSENT` é obrigatório;
- `PROVISIONAL` é desejável após o fluxo principal estar pronto;
- `MATURE` é definido no domínio e na documentação, mas fica fora da implementação inicial.

### Candidate

QueryFamily elegível e priorizada para investigação.

Uma Candidate possui evidências de impacto, mas ainda não é uma conclusão nem uma recomendação.
Ela pode não gerar um AnalysisCase se faltarem evidências mínimas ou se já houver um caso equivalente em andamento.

### AnalysisCase

Agregado auditável que representa uma investigação delimitada sobre uma Candidate.

Um AnalysisCase possui:
- identidade própria;
- estado atual;
- histórico imutável de eventos;
- evidências associadas;
- diagnóstico, se existente;
- hipótese, se existente;
- resultado de validação, se existente;
- decisão humana, se existente.

ObservedQuery responde "o que foi observado?".
AnalysisCase responde "o que o Vigil e a curadoria fizeram diante disso?"

### Evidence

Artefato factual associado a um AnalysisCase.

Uma Evidence deve possuir identidade, tipo, origem, instante de coleta e conteúdo ou referência segura ao conteúdo.

Exemplos:
- delta de métricas de uma ObservationWindow;
- ImpactAssessment;
- plano `EXPLAIN (FORMAT JSON)`;
- RelationMetadata;
- metadados de índices;
- resultado de pré-requisitos do target;
- resultado de sanitização;
- resultado de validação.

Uma Evidence não contém uma conclusão subjetiva. Diagnósticos e hipóteses podem referenciar evidências, mas não substituí-las.

### QueryPlan

Representação estruturada de um plano de execução coletado do target ou do sandbox.

No MVP, o target monitorado usa `EXPLAIN (FORMAT JSON)` sem `ANALYZE` como padrão.
O Vigil não deve executar `EXPLAIN ANALYZE` indiscriminadamente em produção.

## Tipos e origem das métricas

O Vigil diferencia métricas estimadas, métricas observadas no target e métricas medidas no sandbox.

### EstimatedPlanMetrics

Métricas estimadas pelo otimizador, obtidas com:

```sql
EXPLAIN (FORMAT JSON)
```
no target monitorado. Exemplos:
- tipo de nó, como Sequential Scan, Index Scan ou Hash Join;
- custo etimado de ínicio e custo toal;
- linhas estimadas;
- largura estimada de linhas;
- ordem dos joins;
- índice escolhido, quando existente;
- condições de filtro, join e índice.

Essas métricas não executam a consulta. Elas descrevem a decisão que o planner tomaria usando as estatísticas já existentes no PostgreSQL.

### ObservedExecutionMetrics

Métricas agregadas de execuções reais já ocorridas no target, obtidas principalmente por snapshots e deltas de `pg_stat_statements`.

Exemplos:
- quantidade de chamadas;
- tempo total de execução;
- tempo médio derivado da janela;
- linhas retornadas;
- blocos compartilhados lidos, gravados e encontrados em cache;
- blocos temporários lidos e gravados, quando disponíveis;
- tempo de planejamento, quando disponível.

Essas métricas mostram o impacto real agregado da `QueryFamily`, mas não mostram tempo e linhas reais por nó individual do plano.

### SandboxExecutionMetrics

Métricas reais por nó do plano, obtidas somente no Sandbox isolado.

Exemplo de comando permitido no sandbox:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT ...
```

Exemplos de métricas:
- linhas reais por nó;
- loops;
- tempo real por nó;
- tempo total de execução;
- buffers lidos e encontrados em cache;
- diferença entre cardinalidade estimada e cardinalidade real.

SandboxExecutionMetrics não são apresentadas como medição de produção. Elas devem registrar versào do PostgreSQL, seed, schema, índices, configuração e nível de fidelidade do ambiente.

No target real, combinaremos:

```text
pg_stat_statements
+ snapshots e deltas do Vigil
+ EXPLAIN (FORMAT JSON), sem ANALYZE
+ catálogo de relações e índices
```

Isso permite saber:
> A consulta está consumido tempo e I/O reais?
> Qual é sua participação no workload?
> O planner escolheu Seq Scan, Hash Join ou outro operador?
> Quantas linhas e qual custo o planner estimou?
> Existem índices compatíveis?

O que não saberemos no target sem executar a query outra vez:
> Quantas linhas reais cada nó retornou?
> Quanto tempo real cada nó gastou?
> Qual foi exatamente a divergência entre estimatiza e execução naquele momento?

Esses últimos dados vêm do `EXPLAIN ANALYZE` no Sandbox, não do ambiente monitorado.
`pg_stat_statements` fornece estatísticas agregadas de execuções reais, enquanto `EXPLAIN ANALYZE`
oferece instrumentaçào por nó ao executar novamente a consulta.

> Como iremos conseguir as métricas presentes no comando de ANALYZE, sem executar ANALYZE?

### SanitizationResult

Resultado explícito da avaliação de segurança do contexto.

Possíveis resultados conceituais:
- `SAFE`: o contexto minimizado pode seguir para análise permitida.
- `REDACTED`: o contexto foi parcialmente removido e possui limitações.
- `BLOCKED`: o contexto não pode ser processado fora da fronteira confiável.

### SanitizedContext

Contexto mínimo derivado de Evidence e aprovado pela política de sanitização.

Pode conter, por exemplo:
- métricas agregadas;
- operadores relevantes do plano;
- cardinalidades estimadas;
- metadados pseudonimizados de relações e índices;
- limitações e evidências disponíveis.

Nunca deve conter RawSql, valores literais, credenciais ou segredos.

### Finding

Condição técnica identificada por uma regra determinística ou por análise estruturada.

Exemplo: `POSSIBLE_AVOIDABLE_SEQ_SCAN`

Um Finding indica que existe algo a investigar. Ele não é, isoladamente, um diagnóstico definitivo.

> E as inferências feitas pelo modelo de IA? Não são findings? Se não, são o que?

### Diagnosis

Conclusão explicativa e rastreável sobre a provável causa de impacto.

Um Diagnosis deve:
- referenciar Evidence existente;
- indicar limitações e nível de confiança
- diferenciar fato observado de inferência;
- não propor mudanças sem uma hipótese separada;

Exemplo conceitual:
> A consulta apresenta alto impacto na janela observada e o plano indica Sequential Scan
> em uma relação massiva. Não há índice compatível identificado para o predicado analisado.

### OptimizationHypothesis

Proposta técnica, estruturada e testável para reduizr o impacto identificado.

Exemplos:
- criação hipotética de índice
- ajuste de índice existente;
- reescrita estrutural de consulta;
- coleta de estatísticas, quando aplicável.

Uma OptimizationHypothesis não é SQL executável automaticamente no target.

### SchemaBlueprint

Representação estrutural e limitada das relações necessárias para validar uma `OptimizationHypothesis` em Sandbox.
Um SchemaBlueprint pode conter:
- tipo da relação;
- colunas, tipos, nulidade e propriedades relevantes;
- chaves e constraints necessárias;
- índices existentes;
- estimativas de cardinalidade e páginas;
- versão do PostgreSQL;
- mapeamento pseudonimizado de identificadores.

SchemaBlueprint é derivado por leitura do target. Ele não contém linhas reais, valores de negócio, credencias ou SQL bruto persistido.

A cadeia será:
```text
Target PostgreSQL
    -> Leitura de metadados relevantes
    -> SchemaBlueprint
    -> PostgreSQL em Testcontainers
    -> CREATE TABLE / INSERT sintético / CREATE INDEX
    -> EXPLAIN ANALYZE no Sandbox
```

#### Privacidade dos nomes de negócio

Nomes de colunas como `cpf`, `customer_risk_score` ou `revenue_margin` também podem ser sensíveis.

Por isso, a melhor abordagem é criar um mapeamento temporário e local:
```text
usarios      -> relation_1
cpf          -> column_1
created_at   -> column_2
```

O adapter mantém o mapeamento apenas durante a criação do Sandbox e reescreve a consulta normalizada para os nomes sintéticos. O core persiste apenas a representação sanitizada necessária para auditoria.

Isso é mais seguro do que persistir um `CREATE TABLE` original ou enviar definições reais ao modelo.

### Sandbox

Ambiente descartável e isolado usado para validar hipóteses.
No MVP, o Sandbox é criado com Testcontainers e utiliza dados sintéticos,
schema controlado e configuração registrada.

O Ssandbox nunca reutiliza a credencial nem o endpoint do target monitorado.

### ValidationResult

Resultado registrado da avaliação de uma OptimizationHypothesis no Sandbox.

Resultados possíveis:
- `VALIDATED`: benefício estimado foi observado dentro dos critérios definidos.
- `INVALIDATED`: a hipótese não demonstrou benefício ou não se sustentou.
- `INCONCLUSIVE`: não houve evidência suficiente para concluir com segurança.

> Em quais casos por exemplo, podemos chegar em um ValidationResult `INCONCLUSIVE`?

`VAlIDATED` não significa que a hipótese foi aplicada em produção nem que o benefício é garantido no target real.

### Recommendation

Material preparado para a curadoria humana.

Uma Recommendation consolida:
- diagnóstico;
- hipótese;
- evidências relevantes;
- resultado de validação;
- limitações;
- risco e escopo da alteração sugerida.

### HumanDecision

Decisão registrada por uma pessoa responsável pela curadoria.

Valores possíveis:
- `APPROVED`;
- `REJECTED`;
- `CHANGES_REQUESTED`;

Toda decisão humana contém ator, motivo e instante.

### CaseEvent

Evento imutável que registra uma transição ou fato relevante no ciclo de vida de um AnalysisCase.

Um CaseEvent contém, no mínimo:
- estado anterior, quando aplicável;
- estado posterior, quando aplicável;
- ator;
- motivo;
- instante;
- referência aos artefatos que justificam a mudança, quando aplicável;

### Regras de Nomenclatura
- Use `target`, nunca `database`, para designar o banco monitorado como um todo.
- Use `relation`, nunca apenas `table`, quando o conceito inclui tabelas e Materialized Views.
- Use `evidence` para fatos e `diagnosis` para conclusões.
- use `hypothesis` antes de `validation`; use `recommendation` apenas quando houver material para curadoria.
- use `validated` somente para resultado de sandbox, nunca como sinônimo de "aprovado"
- use `approved` somente para decisào humana.
- Nunca chame uma Candidate de "query ruim" ou "problema confirmado".
- Nunca use RawSql como identificador persistido de uma QueryFamily.

### Invariantes transversais
- O Vigil não executa DDL ou DML no target monitorado.
- Toda mudança de estado de AnalysisCase é auditável.
- Todo Diagnosis deve apontar para evidências reais do caso.
- Toda hipótese é validada somente em Sandbox ou marcada como não validável.
- O contexto enviado a inferência deve possuir SanitizationResult permitido.
- Falha, timeout ou saída inválida de inferência sào fatos auditáveis; não são diagnósticos.
- A ausência de baseline não impede análise de impacto absoluto, mas reduz a confiança de comparações históricas.

| Termo                    | Definição                                                                                                                  |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `ObservedQuery`          | Uma família de consultas observada na telemetria do banco, identificada por fingerprint. Não é automaticamente um problema |
| `AnalysisCase`           | Agregado auditável que reúne a investigação de uma consulta candidata. É o objeto que possui ciclo de vida e estados.      |
| `Evidence`               | Dado factual que sustenta uma análise: métricas, delta de snapshot, plano, metadados de índices, relações, etc.            |
| `SanitizedContext`       | Contexto seguro e minimizado derivado das evidências, permitido para diagnóstico e possível inferência externa.            |
| `Diagnosis`              | Explicação sobre o possível problema, sempre vinculada a evidências.                                                       |
| `OptimizationHypothesis` | Proposta testável de melhoria, como um índice hipotético ou reescrita de uma consulta. Não é uma alteração aplicada.       |
| `ValidationResult`       | Resultado da vlidação da hipótese em sandbox isolado.                                                                      |
| `Recommendation`         | Proposta apresentada à curadoria humana, incluindo limitações, evidências e resultado da validação.                        |
| `HumanDecision`          | Decisão de uma pessoa responsável: aprovar, rejeitar ou solicitar mudanças.                                                |
| `CaseEvent`              | Registro imutável de uma mudança de estado, com ator, motivo e instante.                                                   |

## Contrapartida Java dos termos adicionados em tcc-final-5

| Termo do domínio | Tipo Java | Garantia executável |
|---|---|---|
| Nó de plano sanitizado | `PlanNodeEvidence` | Persiste somente referências pseudonimizadas e fatos estruturais; nunca o texto do filtro. |
| Regra diagnóstica | `DiagnosticRuleCode` e `DiagnosticRuleEngine` | Avaliação pura, determinística e ligada a evidence. |
| Limiar diagnóstico | `DiagnosticRuleThresholds` | Política explícita: relação mínima de 10.000 linhas e seletividade máxima de 10% por padrão. |
| Finding | `Finding` | Código estável, severidade, explicação segura e IDs de evidence não vazios. |
| Inconclusão diagnóstica | `DiagnosticInconclusiveReason` | Explica qual pré-condição comprovável faltou sem inventar finding. |
| Diagnosis | `Diagnosis` | Agrega somente findings e evidências existentes no caso. |
| OptimizationHypothesis | `OptimizationHypothesis` | Mantém relação/colunas seguras e finding de origem; não é recomendação nem DDL. |
| QueryShape | `QueryShape` | Classifica a forma da consulta; shapes não suportados são bloqueados antes da validação. |
| FidelityLevel | `FidelityLevel` | Declara a fidelidade do experimento e impede confundir evidência sintética com target real. |
| Recommendation | `Recommendation` | Material versionado com hipótese, resultado, evidências, riscos e limitações. |
| Risco da recomendação | `RecommendationRisk` | Risco explícito para a curadoria, sem aplicação automática. |
| Limitação da recomendação | `RecommendationLimitation` | Limite epistemológico explícito, inclusive dados sintéticos e ausência de medição no target. |
| Contexto de inferência | `SanitizedInferenceContext` | Único contexto aceito pela porta; contém somente dados aprovados e pseudonimizados. |
| Versão do prompt | `PromptTemplateVersion` | Torna o template reproduzível e impede prompt livre no chamador. |
| Saída fundamentada | `InferencePort.GroundedOutput` | Só é aceita depois de schema, evidence e referências seguras serem validados. |
