# ADR-005 — Sanitização fail-closed com `SanitizationResult` SAFE/BLOCKED

- **Status:** Aceito
- **Data:** 2026-11-25
- **Decisores:** Equipe Vigil
- **Contexto:** Vigil Core / Sanitização de contexto e privacidade (RF20)

## Contexto

O RF20 exige "executar sanitização fail-closed e gerar `SanitizationResult` SAFE ou BLOCKED com motivo". Até então não existia `SanitizationResult`: havia o `PostgresSelectStatementGuard`, que valida por **exceção** (`PlanCollectionRejected`) e é específico da coleta de EXPLAIN, e o normalizador do fingerprint (`SqlCanonicalizer`, ADR-004), que remove comentários e troca literais por placeholders. Faltava um resultado explícito de sanitização e a remoção de comentários/literais num único componente reutilizável.

## Decisão

Introduzir um resultado de domínio explícito e uma porta de aplicação, com implementação que **reutiliza** o que já existe.

- **Domínio:** `SanitizationResult` selado — `Safe(sanitizedContent, appliedRules)` | `Blocked(reason)`. `SanitizationRule` e `SanitizationBlockedReason` são enums. O detalhe do `Blocked` é **derivado do enum** (`reason.detail()`), nunca texto livre: a garantia de "sem SQL nem literal" vale por construção. O domínio não conhece parser nem crypto.
- **Aplicação:** porta `QuerySanitizer` (`sanitize(String) → SanitizationResult`). Nunca lança para conteúdo recusado — recusa é `Blocked`.
- **Adaptador:** `QuerySanitizerAdapter` encadeia, sem duplicar lógica, (1) a política do `PostgresSelectStatementGuard` e (2) o `SqlCanonicalizer`. Uma recusa do guard vira `Blocked` com o motivo mapeado; "não suportado" do normalizador vira `Blocked`. Qualquer imprevisto é `Blocked(INTERNAL_ERROR)` — nunca `Safe`, nunca repasse do texto bruto.

### Reuso do guard sem alterar seu comportamento

O guard rejeitava placeholders (`$1`) e lançava na primeira ocorrência. Para reutilizá-lo sem duplicar, extraiu-se um método interno `scan(text, placeholdersAllowed)`; `validate(text) = scan(text, false)` permanece **idêntico** (placeholders rejeitados, 91 testes intactos) para a coleta de EXPLAIN. O sanitizador chama `scan(text, true)`.

**Diferença deliberada:** o sanitizador aceita `$1` porque o texto de `pg_stat_statements` já é normalizado; o EXPLAIN continua rejeitando placeholders, pois não há binds para planejar.

### Integração com a máquina de estados (sem alterá-la)

A transição `AnalysisCase.sanitize(...)` passou a exigir, por tipo, um `SanitizationResult.Safe`; um `Blocked` não pode ser passado, então o caso **nunca avança** em conteúdo recusado. A partir de `EVIDENCE_COLLECTED` não existe transição terminal de rejeição com motivo (só `SANITIZED` ou `ARCHIVED`), e `ARCHIVED` é um encerramento genérico cuja semântica não deve ser sobrecarregada. Portanto, no `Blocked` o caso **permanece em `EVIDENCE_COLLECTED`**: o caso de uso `SanitizeCaseUseCase` devolve o `Blocked` ao chamador e registra apenas o enum (sem SQL) no log de aplicação. Não há estado, tabela nem tipo de evento novo; `CaseEvent` e `reconstitute()` ficam inalterados.

## Consequências

- Recusa é um valor observável e testável, não uma exceção; o fluxo nunca avança com contexto inseguro.
- O SQL bruto nunca é persistido nem logado; o conteúdo `Safe` é a forma canônica sem literais, e o detalhe do `Blocked` é fixo por enum.
- Há uma única política de instrução (o guard), compartilhada entre EXPLAIN e sanitização, divergindo apenas no tratamento de placeholders — documentado.
- Limitação assumida: como não há transição de bloqueio, um `Blocked` não deixa rastro no histórico auditável do caso, apenas no log de aplicação. Caso o produto passe a exigir auditoria de bloqueios, será necessária uma decisão explícita que estenda a máquina de estados.
