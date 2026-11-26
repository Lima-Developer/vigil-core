# Auditoria dos requisitos funcionais — tcc-final-5

Data da auditoria inicial: 2026-11-26. Base: `32ab7c1` (`tcc-final-4`).

O veredito avalia se o código e os testes sustentam a parte que o Quadro A.1 declara como implementada. Itens explicitamente fora do recorte (alvo real, série histórica, Bedrock executado, egress real e overhead) não são cobrados como conclusão do requisito.

| RF | Afirmação do relatório | Implementação encontrada na base | Teste/artefato que prova | Antes | Lacuna inicial |
|---|---|---|---|---|---|
| RF01 | Um alvo PostgreSQL/Aurora por instalação | `TargetProfile`, `VigilConfiguration`, `TargetProfileLoader` | `TargetProfileTest`, `TargetProfileLoaderTest` | VERDADEIRO | — |
| RF02 | Validar configuração obrigatória sem vazar credenciais | `TargetProfile`, `PostgresTargetSettings`, `PostgresPasswordSource` | `TargetProfileTest`, `PostgresTargetSettingsTest`, `PostgresPasswordSourceTest` | VERDADEIRO | — |
| RF03 | Preflight READY/DEGRADED/BLOCKED | `CheckPostgresPreflight`, `PostgresPreflightReport` | `CheckPostgresPreflightTest`, `PostgresPreflightIT` | VERDADEIRO | — |
| RF04 | Acesso de menor privilégio, read-only e timeouts | `PostgresConnectionFactory`, `JdbcPostgresCapabilityProbe` | `PostgresConnectionFactoryIT`, `PostgresPreflightIT` | VERDADEIRO | — |
| RF05 | Coletar `pg_stat_statements` sem resultados de negócio | `PgStatStatementsTelemetryAdapter` | `PgStatStatementsTelemetryAdapterIT`, `PgStatStatementsTelemetryAdapterTest` | PARCIAL | Depende da fonte configurada, como declarado. |
| RF06 | Snapshot por janela e deltas acumulados | `TelemetrySnapshot`, `QueryTelemetryDeltaCalculator`, `ContinuousTelemetryCollector` | `TelemetrySnapshotTest`, `QueryTelemetryDeltaCalculatorTest`, `ContinuousTelemetryCollectorTest` | PARCIAL | Coletor é opcional, como declarado. |
| RF07 | Histórico local com retenção aplicada | `SqliteWorkspaceTelemetryHistory`; `ContinuousTelemetryCollector` fornece cutoff e limite | `SqliteWorkspaceTelemetryHistoryTest`, `ContinuousTelemetryCollectorTest` | VERDADEIRO | — |
| RF08 | Contexto de carga do alvo | `PostgresDatabaseActivityAdapter`, `DatabaseActivitySnapshot` | `PostgresDatabaseActivityAdapterTest`, `WorkspaceTelemetryControllerTest` | PARCIAL | Disponível apenas quando o adaptador fornece métricas. |
| RF09 | Seleção por impacto e, quando houver, regressão | `ImpactSelectionPolicy`, `SelectCandidatesByImpactUseCase` | `ImpactSelectionPolicyTest`, `SelectCandidatesByImpactUseCaseTest`, `ImpactSelectionIT` | PARCIAL | Regressão histórica fora do escopo. |
| RF10 | `BaselineConfidence` compatível com o histórico | `BaselineConfidence`, `ImpactAssessment`; política atual registra `ABSENT` | `ImpactDomainValidationTest`, `ImpactSelectionPolicyTest` | PARCIAL | Estrutura existe; maturação histórica não existe. |
| RF11 | Fingerprint versionado sem SQL bruto | `QueryFingerprint`, `HmacQueryFingerprinter`, `SqlCanonicalizer` | `HmacQueryFingerprinterTest`, `SqlCanonicalizerTest` | VERDADEIRO | — |
| RF12 | `AnalysisCase` auditável e persistido | `AnalysisCase`, `SqliteAnalysisCaseRepository` | `AnalysisCaseTest`, `SqliteAnalysisCaseRepositoryIT` | VERDADEIRO | — |
| RF13 | `CaseEvent` imutável em toda transição | `CaseEvent`, transições nomeadas de `AnalysisCase` | `AnalysisCaseLifecycleTest`, `AnalysisCaseTest` | VERDADEIRO | — |
| RF14 | `Evidence` tipada com metadados | `Evidence`, `EvidencePayload`, `EvidencePayloadCodec` | `EvidenceTest`, `EvidenceRowMapperTest` | VERDADEIRO | — |
| RF15 | Metadados de relações anexados ao caso | `SchemaBlueprint`, `RelationBlueprint`, `PostgresSchemaCatalogCollector` | `SchemaBlueprintTest`, `PostgresSchemaCatalogCollectorIT` | PARCIAL | Blueprint não era anexado ao caso nem correlacionado ao plano. |
| RF16 | `EXPLAIN FORMAT JSON` somente para SELECT e sem ANALYZE | `PostgresSelectStatementGuard`, `PostgresExecutionPlanSource` | `PostgresSelectStatementGuardTest`, `PostgresExecutionPlanSourceIT` | VERDADEIRO | — |
| RF17 | Plano JSON convertido para modelo interno | `PostgresExplainPlanParser`, `PlanSummaryEvidencePayload` | `PostgresExplainPlanParserTest` | VERDADEIRO | — |
| RF18 | Classificar `QueryShape` e bloquear shape não suportado | Apenas guard sintático; não há `QueryShape` | — | AUSENTE | Criar classificação explícita e teste de bloqueio. |
| RF19 | Normalização reproduzível | `SqlCanonicalizer`, `HmacQueryFingerprinter` | `SqlCanonicalizerTest`, `HmacQueryFingerprinterTest` | VERDADEIRO | — |
| RF20 | Sanitização fail-closed SAFE/BLOCKED | `SanitizationResult`, `QuerySanitizerAdapter`, `SanitizeCaseUseCase` | `SanitizationResultTest`, `QuerySanitizerAdapterTest`, `SanitizeCaseUseCaseTest` | VERDADEIRO | — |
| RF21 | Pseudonimizar identificadores | `Pseudonymizer`, referências `Safe*` | `PseudonymizerTest`, `SchemaBlueprintAssemblerTest` | VERDADEIRO | — |
| RF22 | Regras determinísticas geram `Finding` ligado a Evidence | Não há `Finding` nem motor de regras | — | AUSENTE | Implementar Parte D. |
| RF23 | `Diagnosis` somente de findings/evidências válidos | Não há `Diagnosis`; transição aceita apenas metadados | — | AUSENTE | Implementar Parte D. |
| RF24 | `OptimizationHypothesis` separada de recomendação/execução | Não há tipo de domínio; transição aceita apenas metadados | — | AUSENTE | Implementar Parte D. |
| RF25 | `SchemaBlueprint` mínimo e sanitizado | Blueprint/pseudonimização existem | `SchemaBlueprintTest`, `RelationSelectorTest` | PARCIAL | Não era evidence do caso nem limitado pelas relações do plano. |
| RF26 | Sandbox PostgreSQL efêmero sem alvo real | `tools/hypopg-lab`, `PostgresLaboratory` | `PostgresLaboratoryIT`, artefatos do laboratório | VERDADEIRO | — |
| RF27 | Dados sintéticos por seed e `FidelityLevel` | Dataset e seed reproduzíveis existem | `LaboratoryDatasetTest`, `PostgresLaboratoryIT` | PARCIAL | Não há `FidelityLevel` em Java nem registro no fluxo. |
| RF28 | HypoPG/índice descartável no laboratório | `run_hypopg_scenario.py`, `run_physical_index_scenario.py` | artefatos versionados de `tools/hypopg-lab` | VERDADEIRO | — |
| RF29 | Comparação antes/depois com três resultados | `ArtifactHypothesisValidationAdapter`, `ValidationOutcome` | `ArtifactHypothesisValidationAdapterTest` | VERDADEIRO | — |
| RF30 | Porta independente e recusa sem contexto SAFE | `InferencePort`, `OllamaInferenceAdapter` | `OllamaInferenceAdapterTest` | PARCIAL | A requisição aceita prompt `String` livre e não prova bloqueio antes do HTTP. |
| RF31 | Adaptador local configurável Ollama/vLLM | `OllamaInferenceAdapter`, configuração de endpoint | `OllamaInferenceAdapterTest` | VERDADEIRO | — |
| RF32 | Adaptador Bedrock opcional desacoplado | Só a porta; não há adaptador Bedrock | — | AUSENTE | Criar adaptador mínimo desligado por padrão e cliente falso. |
| RF33 | Contexto mínimo e prompt versionado | Runners do laboratório constroem contexto sintético; Java aceita prompt livre | testes/artefatos de `tools/complex-query-lab` | PARCIAL | Criar contexto aprovado e versão de template em Java. |
| RF34 | Validar schema e grounding da resposta | Validação existe nos runners, não na fronteira Java | testes dos runners | PARCIAL | Criar validador Java de schema e IDs de evidence. |
| RF35 | Rejeitar saída fora do contrato/evidence inexistente | Não há validador de resposta de produção | — | AUSENTE | Rejeitar sem alterar estado do caso. |
| RF36 | `Recommendation` com material, riscos e limitações | Apenas transição `prepareRecommendation(TransitionMetadata)` | `AnalysisCaseTest` cobre só o estado | AUSENTE | Criar tipo e pré-condições. |
| RF37 | Decisão humana com ator, motivo e versão da recomendação | `HumanDecision`, `RecordHumanDecisionCommand` | `AnalysisCaseUseCasesTest`, `SqliteAnalysisCaseUseCasesIT` | PARCIAL | Falta exigir versão da recomendação. |
| RF38 | REST de health, casos e decisão; recomendações fora do recorte | `DemoCaseController`, endpoints de health/telemetria | `DemoCaseHttpIT`, `DemoCaseControllerTest` | PARCIAL | Recorte declarado não inclui REST completo de recomendações. |
| RF39 | Curadoria mínima com cadeia de evidência em seis abas | `index.html`, `workspace.js` com Visão Geral, Casos, Auditoria, Telemetria, Uso de IA e Roadmap | `VigilApplicationTest`, checklist da demo | VERDADEIRO | — |
| RF40 | SQLite com migrações e concorrência | migrations, `SqliteAnalysisCaseRepository`, versão otimista | `SqlMigrationRunnerTest`, `SqliteWalConcurrencyTest`, `SqliteAnalysisCaseRepositoryIT` | VERDADEIRO | — |
| RF41 | WAL, integridade e backup consistente documentado | `SqliteConnectionFactory`, `SqliteStorageProbe`, `SqliteBackup`, `docs/architecture/sqlite-operacao.md` | `SqliteConnectionFactoryTest`, `SqliteBackupTest`, `SqliteStorageProbeTest` | VERDADEIRO | Implementação usa `quick_check`, equivalente operacional conservador. |
| RF42 | Logs/métricas sem conteúdo sensível | logs por códigos, telemetria agregada, sanitização | `SanitizeCaseUseCaseTest`, `WorkspaceTelemetryControllerTest` | VERDADEIRO | — |
| RF43 | Docker com configuração externa e volume SQLite | Configuração externa existe; Dockerfile e volume não | — | AUSENTE | Criar imagem e declarar volume. |
| RF44 | Compose com Vigil e PostgreSQL sintético | Não há arquivo Compose | — | AUSENTE | Criar compose demonstrável e teste de configuração. |
| RF45 | Runner B0–B3 reproduzível e estruturado | `tools/complex-query-lab/run*.py`, schemas, dados versionados | artefatos de `docs/experiments` e scripts de sumarização | VERDADEIRO | — |
| RF46 | E2E da telemetria à decisão sem alterar alvo | Há ITs de trechos, não do fluxo completo | `ImpactSelectionIT`, `DemoCaseHttpIT`, `SqliteAnalysisCaseUseCasesIT` | PARCIAL | Criar E2E único com duas coletas, finding, hipótese, validação, recomendação e decisão. |

## Resultado inicial

- VERDADEIRO: 23
- PARCIAL: 14
- AUSENTE: 9

## Resultado final

| RF | Depois | Evidência que fecha ou delimita o requisito |
|---|---|---|
| RF01 | VERDADEIRO | `TargetProfileTest`, `TargetProfileLoaderTest` |
| RF02 | VERDADEIRO | `PostgresTargetSettingsTest`, `PostgresPasswordSourceTest` |
| RF03 | VERDADEIRO | `CheckPostgresPreflightTest`, `PostgresPreflightIT` |
| RF04 | VERDADEIRO | `PostgresConnectionFactoryIT`, `PostgresPreflightIT` |
| RF05 | PARCIAL | Coleta por `PgStatStatementsTelemetryAdapter`; dependência da fonte permanece declarada. |
| RF06 | PARCIAL | Snapshot/delta completos; coletor contínuo permanece opcional como declarado. |
| RF07 | VERDADEIRO | `SqliteWorkspaceTelemetryHistoryTest` |
| RF08 | PARCIAL | Contexto disponível por `PostgresDatabaseActivityAdapter` quando suportado pelo target. |
| RF09 | PARCIAL | Impacto absoluto implementado; regressão histórica permanece fora do recorte. |
| RF10 | PARCIAL | `BaselineConfidence` existe; baseline histórico maduro permanece fora do recorte. |
| RF11 | VERDADEIRO | `HmacQueryFingerprinterTest`, `SqlCanonicalizerTest` |
| RF12 | VERDADEIRO | `SqliteAnalysisCaseRepositoryIT` |
| RF13 | VERDADEIRO | `AnalysisCaseLifecycleTest` |
| RF14 | VERDADEIRO | `EvidenceRowMapperTest` e compatibilidade de payload legado em `EvidencePayloadCodec` |
| RF15 | VERDADEIRO | `RelationMetadataEvidencePayload.fromBlueprint`, `EvidenceRowMapperTest` |
| RF16 | VERDADEIRO | `PostgresSelectStatementGuardTest`, `PostgresExecutionPlanSourceIT` |
| RF17 | VERDADEIRO | `PostgresExplainPlanParserTest` cobre fatos por nó e ambiguidade fail-closed. |
| RF18 | VERDADEIRO | `PostgresQueryShapeClassifier`, `PostgresQueryShapeClassifierTest`, `ValidateAnalysisCaseUseCase` |
| RF19 | VERDADEIRO | `SqlCanonicalizerTest`, `HmacQueryFingerprinterTest` |
| RF20 | VERDADEIRO | `QuerySanitizerAdapterTest`, `SanitizeCaseUseCaseTest` |
| RF21 | VERDADEIRO | `PseudonymizerTest`; parser e catálogo compartilham o mesmo pseudonimizador. |
| RF22 | VERDADEIRO | `DiagnosticRuleEngine`, `DiagnosticRuleEngineTest` |
| RF23 | VERDADEIRO | `Diagnosis`, `DiagnoseCaseUseCase`, `DiagnosisHypothesisTest` |
| RF24 | VERDADEIRO | `OptimizationHypothesis`, `ProposeHypothesisUseCase`, `DiagnosisHypothesisTest` |
| RF25 | VERDADEIRO | `RelationBlueprint` anexado como `RELATION_METADATA` sem migração; round-trip em `EvidenceRowMapperTest`. |
| RF26 | VERDADEIRO | `PostgresLaboratoryIT` e laboratório HypoPG existente. |
| RF27 | VERDADEIRO | `FidelityLevel`, `ValidationAssessment`, testes do adaptador de validação. |
| RF28 | VERDADEIRO | Runners HypoPG/índice físico e artefatos versionados. |
| RF29 | VERDADEIRO | `ArtifactHypothesisValidationAdapterTest` |
| RF30 | VERDADEIRO | `InferencePort.InferenceRequest` exige `SanitizedInferenceContext`; regra em `ArchitectureTest`. |
| RF31 | VERDADEIRO | `OllamaInferenceAdapterTest` |
| RF32 | VERDADEIRO | `BedrockInferenceAdapter`, `BedrockRuntimeClient`, `BedrockInferenceAdapterTest`; desligado por padrão e sem AWS real. |
| RF33 | VERDADEIRO | `SanitizedInferenceContext`, `PromptTemplateVersion`, testes dos adaptadores. |
| RF34 | VERDADEIRO | `InferenceOutputValidatorTest` cobre schema e grounding. |
| RF35 | VERDADEIRO | `InferenceOutputValidatorTest` cobre evidence/referências inexistentes e conteúdo proibido. |
| RF36 | VERDADEIRO | `Recommendation` e precondições em `AnalysisCaseLifecycleTest`. |
| RF37 | VERDADEIRO | `RecordHumanDecisionCommand` exige versão; persistência/reconstituição cobertas por ITs SQLite. |
| RF38 | PARCIAL | REST de caso e decisão funciona; API completa de recomendações segue fora do recorte declarado. |
| RF39 | VERDADEIRO | Interface com seis abas e cadeia de evidência; testes bootstrap. |
| RF40 | VERDADEIRO | Migrações existentes, WAL e concorrência; nenhuma migração nova foi necessária. |
| RF41 | VERDADEIRO | `SqliteBackupTest`, `SqliteStorageProbeTest`, documentação operacional. |
| RF42 | VERDADEIRO | Testes de sanitização, parser e inferência verificam ausência de SQL/literais. |
| RF43 | VERDADEIRO | `Dockerfile`, `.dockerignore`, `ContainerPackagingTest`; build e execução não-root validados. |
| RF44 | VERDADEIRO | `compose.yml`, `docker/demo/init.sql`, `docker compose config` e execução saudável dos dois serviços. |
| RF45 | VERDADEIRO | Runner B0–B3 e artefatos reproduzíveis preservados. |
| RF46 | VERDADEIRO | `VigilWorkflowE2EIT` e fluxo Compose/API até `APPROVED`, inclusive persistência após restart. |

Totais finais: **40 VERDADEIRO, 6 PARCIAL, 0 AUSENTE**.

### Lacunas fechadas

- Parte D: `DiagnosticRuleEngine`, `Finding`, `Diagnosis` e `OptimizationHypothesis` com grounding estrito.
- Evidência aditiva: plano por nó e `RelationBlueprint` usando tipos persistidos existentes; payload legado continua legível e não houve migração SQLite.
- Fronteira de inferência: contexto aprovado, template versionado, schema/grounding validados, Ollama e Bedrock opcional com cliente falso.
- Curadoria: `QueryShape`, `FidelityLevel`, `Recommendation` concreta e decisão vinculada à versão.
- Empacotamento: imagem não-root, volume SQLite e Compose com PostgreSQL sintético reproduzível.
- E2E: duas coletas, seleção por impacto, finding, hipótese, validação, recomendação, decisão humana e reconstituição persistida.

### Antes e depois

| Momento | VERDADEIRO | PARCIAL | AUSENTE |
|---|---:|---:|---:|
| Base `tcc-final-4` (`32ab7c1`) | 23 | 14 | 9 |
| Implementação `tcc-final-5` | 40 | 6 | 0 |

Permanecem deliberadamente fora do recorte: target real, regressão histórica, chamada real ao Bedrock, medição real de egress, comparação de schema por Codex/Claude e overhead do Vigil.
