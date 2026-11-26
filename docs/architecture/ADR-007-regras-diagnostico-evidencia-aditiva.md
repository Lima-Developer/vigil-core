# ADR-007 — Regras diagnósticas e evolução aditiva de Evidence

- Status: aceita
- Data: 2026-11-26

## Contexto

O fluxo precisava produzir `Finding`, `Diagnosis` e `OptimizationHypothesis` rastreáveis sem persistir SQL bruto, literais de filtros ou nomes de negócio. O plano anterior guardava apenas um resumo global e o SQLite existente restringe os tipos de evidence por `CHECK`; criar um tipo novo exigiria migração de schema fora do recorte autorizado.

## Decisão

1. `PlanSummaryEvidencePayload` evolui de forma aditiva com `PlanNodeEvidence`: tipo do nó, relação e colunas pseudonimizadas, presença de filtro, conhecimento das colunas e linhas estimadas. O texto do predicado nunca é persistido.
2. `RelationMetadataEvidencePayload` passa a aceitar um `RelationBlueprint` sanitizado usando o tipo existente `RELATION_METADATA`. Payloads antigos continuam decodificáveis.
3. `DiagnosticRuleEngine` é puro e determinístico. Sua primeira regra, `SEQ_SCAN_SELECTIVE_FILTER`, exige simultaneamente impacto, relação grande, filtro seletivo, ausência de índice compatível e referências seguras conhecidas.
4. Evidência insuficiente produz razões explícitas de inconclusão; não produz finding por aproximação.
5. `Diagnosis` só aceita findings apoiados em evidências do caso. `OptimizationHypothesis` referencia o finding de origem e continua separada de `Recommendation`.
6. Os limiares iniciais são configurados por `DiagnosticRuleThresholds`: 10.000 linhas mínimas e seletividade máxima de 10%.

## Consequências

- O schema SQLite permanece inalterado e registros anteriores continuam legíveis.
- A correlação plano–catálogo é possível sem expor identificadores reais.
- Casos ambíguos falham fechados como inconclusivos.
- Novas regras devem manter determinismo, grounding e mensagens sem SQL/literais.
