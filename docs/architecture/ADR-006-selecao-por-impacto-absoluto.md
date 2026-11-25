# ADR-006 — Seleção de consultas candidatas por impacto absoluto

- **Status:** Aceito
- **Data:** 2026-11-25
- **Decisores:** Equipe Vigil
- **Contexto:** Vigil Core / Seleção de candidatas (RF09)

## Contexto

O relatório declara a seleção de consultas por impacto absoluto como implementada (objetivo 3, RF09, Apêndice F.2), mas não havia código: `ImpactAssessment`, `Candidate` e `BaselineConfidence` existiam apenas na linguagem ubíqua, e o delta de telemetria era agregado no nível do workspace (`AggregatedTelemetryDelta`), sem identidade por consulta. A abertura de caso partia de uma `ObservedQuery` fixa.

## Decisão

Tornar a seleção real, determinística e no domínio.

- **Domínio (puro):** `ImpactSelectionPolicy` ordena famílias pelo **tempo total de execução** (impacto absoluto), nunca por chamadas; elegibilidade exige **dois** limiares (share mínimo **e** tempo total mínimo); número de chamadas isolado nunca é suficiente (critério 6); desempate estável (tempo desc, chamadas desc, fingerprint asc); resultado vazio é válido com motivo. Tipos: `QueryImpactDelta`, `ImpactAssessment`, `Candidate`, `SelectionReason`, `FamilyImpact`, `ImpactSelectionResult`, `BaselineConfidence`.
- **Limiares padrão:** share ≥ 5%, tempo ≥ 100 ms, top N = 5 (configuráveis).
- **Agregação por família:** `QueryTelemetryDeltaCalculator.perKeyDeltas` expõe deltas por chave nativa reusando as regras de *warming*, **sem alterar** `calculate` nem `AggregatedTelemetryDelta`. As chaves são agrupadas por **fingerprint** (RF11); o `queryid` é só origem.
- **Fingerprint só no caminho de seleção:** um adapter separado (`PgStatStatementsImpactAdapter`) lê o texto do `pg_stat_statements` e calcula o fingerprint **dentro do adapter**; só o fingerprint (ou UNCLASSIFIED) cruza a porta. O coletor contínuo e sua sobrecarga medida **não** mudam. `<insufficient privilege>`, vazio ou não suportado → UNCLASSIFIED (fail-closed), fora do ranking e contado.
- **Snapshots:** o `SelectCandidatesByImpactUseCase` recebe dois snapshots (sem persistência nova; sem mudança de schema). *Warming* → seleção vazia com motivo `WARMING`.
- **Abrir caso:** `DiscoverObservedQueryUseCase` aceita uma `Candidate`; o `SelectionReason` (texto gerado, sem SQL) é o **motivo sugerido da triagem** (`CaseEvent.reason`), não um campo novo no evento de descoberta. A máquina de estados e a persistência do `domain_event` ficam inalteradas. Idempotência permanece pela `discovery_idempotency_key`.

## Consequências

- A seleção por impacto é verdadeira e demonstrável: há um teste de integração (Testcontainers, PostgreSQL 16) em que uma consulta pesada é selecionada e uma barata de muitas chamadas não é.
- O SQL bruto nunca é identidade nem aparece em `Candidate`, motivo ou log.
- **Regressão contra baseline permanece fora do escopo** (modelada): `BaselineConfidence` é sempre `ABSENT` no escopo atual.
- Limitação assumida da escolha "sem schema": não há snapshots por consulta persistidos; o caminho de seleção opera sobre dois snapshots fornecidos ao use case (fixtures na demo; coletas ao vivo numa entrada de CLI).
