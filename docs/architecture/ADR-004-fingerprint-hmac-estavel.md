# ADR-004 — Fingerprint de consulta por normalização canônica e HMAC-SHA256 estável

- **Status:** Aceito
- **Data:** 2026-11-24
- **Decisores:** Equipe Vigil
- **Contexto:** Vigil Core / Identidade de consulta e privacidade

## Contexto

O `QueryFingerprint` era um valor opaco recebido pronto pela `ObservedQuery`; na demonstração e no CLI era fixo. O Vigil precisa de um identificador **estável no tempo** para agrupar e correlacionar a mesma família de consulta entre coletas, sem persistir nem registrar o SQL bruto.

Um identificador nativo como o `queryid` de `pg_stat_statements` não serve a esse papel: depende do PostgreSQL, muda em recriações de objetos e não é estável entre versões maiores. Um hash puro (SHA-256) da forma canônica seria reversível por ataque de dicionário — como o espaço de consultas prováveis contra um esquema é pequeno, um atacante confirmaria consultas conhecidas apenas testando candidatos.

## Decisão

O fingerprint passa a ser **calculado a partir do SQL autorizado**:

```
SQL autorizado → normalização (forma canônica v1, sem literais) → HMAC-SHA256(chave, "v1\n" + canônica) → fingerprint versionado
```

- `value = "hmac-sha256:" + hex minúsculo` (64 caracteres); `algorithmVersion = "v1"`.
- A versão do algoritmo entra na entrada do HMAC (`"v1\n" + ...`), de modo que trocar a versão muda todo fingerprint.

### Separação de responsabilidades (arquitetura hexagonal)

- **Domínio:** `QueryFingerprint` continua um value object puro, sem crypto nem parser. Ganhou apenas validação de formato: `<prefixo-do-algoritmo>:<64 hex minúsculos>`.
- **Aplicação:** porta `QueryFingerprinter` com resultado explícito `FingerprintOutcome` — `Supported(QueryFingerprint)` ou `Unsupported(reason)`. Sem exceção genérica, sem fallback silencioso.
- **Adaptadores:** `SqlCanonicalizer` (normalização v1) e `HmacQueryFingerprinter` (`javax.crypto.Mac`, `HmacSHA256`). Composição no bootstrap.

### Chave

- **Estável por instalação**, vinda de configuração (`VIGIL_FINGERPRINT_KEY` / `vigil.fingerprint.key`), nunca hardcoded. É a estabilidade temporal que permite correlacionar a família de consulta entre coletas.
- É **diferente** da chave de pseudonimização do `SchemaBlueprint`, que é **efêmera por coleta** (justamente para impedir correlação entre coletas). As duas nunca se misturam.
- **Sem chave configurada, o cálculo falha de forma explícita (fail-closed).** A exigência vale apenas para os comandos que calculam fingerprint (o bean é `@Lazy`), não para a inicialização em geral nem para comandos como `triage`/`show`. Apenas o perfil de demonstração declara uma chave de demo fixa no seu arquivo de configuração (`application-demo.yml`), ativado pela demonstração efêmera.

### Regra de normalização notável — `IN (...)` de constantes

Uma lista composta **somente** de constantes em `IN (...)` colapsa para um único placeholder (`IN (1,2,3)` e `IN (7)` → `IN ($n)`). A aridade de uma lista de constantes é tratada como literal irrelevante, como os próprios valores. Uma lista com qualquer não-constante (coluna, subquery) é preservada. As demais regras `v1` estão em [`docs/foundation.md`](../foundation.md).

### Fail-closed

Mais de uma instrução, string/comentário/dollar-quote não terminado, instrução vazia ou qualquer token não reconhecido resultam em `Unsupported` com motivo. O SQL bruto **nunca** é hasheado como fallback.

## Consequências

- O fingerprint é determinístico e estável no tempo para a mesma consulta e chave; muda com estrutura, com a chave e com a versão do algoritmo.
- O SQL bruto e a forma canônica não são persistidos nem logados; apenas o fingerprint.
- O fingerprint **não** é chave única de caso: a chave de descoberta permanece separada, como antes.
- Valores de fingerprint fora do formato (por exemplo, bancos antigos) passam a ser rejeitados como dado corrompido na leitura, de forma consistente com as demais validações de linha.
- O `queryid` da fonte, quando existir, permanece como atributo de contexto da fonte e não substitui o fingerprint.
