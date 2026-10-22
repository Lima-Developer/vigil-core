# Persistência de `AnalysisCase` em SQLite

- **Card:** VIG-023
- **Código:** `vigil-adapters`, pacote `com.vigil.adapters.sqlite` (`SqliteAnalysisCaseRepository` e row mappers)
- **Schema:** [sqlite-schema.md](sqlite-schema.md)

O adapter reconstrói o domínio; rows e tipos JDBC nunca saem do pacote.

## 1. Versão de concorrência

`AnalysisCase.version()` é um `long` independente de `DomainEvent.version()` (String do contrato do evento).

| Situação | Versão |
|---|---|
| Agregado novo, nunca persistido | `0` |
| Primeira gravação | `1` |
| Gravação que persiste algo novo (status, histórico, evidência ou evento de domínio) | `+1` |
| Repetição sem nada novo | inalterada |

A versão em memória e os eventos pendentes só são confirmados (`confirmPersisted`) **depois do `commit`**. Se a
gravação falha, o agregado continua com a versão anterior e os mesmos eventos pendentes, e o mesmo objeto pode ser
salvo de novo. Consumidores não devem drenar eventos com `pullEvents()` antes de salvar; `pendingEvents()` é a
leitura não destrutiva.

## 2. `save`

Uma conexão `BEGIN IMMEDIATE` por operação, uma transação:

1. Lê o estado persistido e classifica os eventos de domínio pendentes por `event_id` (já gravado igual: ignorado;
   gravado com conteúdo diferente: conflito).
2. Caso ausente: exige versão `0`, insere o caso com versão `1`, histórico, evidências e eventos.
3. Caso presente e **idêntico** (mesma identidade, status, histórico e evidências, nenhum evento novo, versão
   persistida `>=` versão do agregado): sucesso sem escrita, mesmo para uma cópia obsoleta. A comparação usa a igualdade
   de valor dos objetos de domínio completos, não só o status.
4. Mesma versão e o persistido é prefixo do agregado: `UPDATE ... SET status = ?, version = version + 1
   WHERE case_id = ? AND version = ?`, verificando que exatamente uma linha foi afetada; depois insere somente o
   sufixo novo de `case_event` (sequência = posição + 1), as evidências novas e os eventos novos.
5. Qualquer outro caso (versão obsoleta divergente, versão à frente do banco, histórico substituído) lança
   `ConcurrentCaseUpdate` sem alterar o banco.
6. `commit`; só então `confirmPersisted(versão, ids dos eventos)`. Em falha: `rollback`, causa preservada.

`saveDiscovered` usa o mesmo fluxo com `INSERT` simples (sem `OR REPLACE`/`OR IGNORE`) incluindo
`discovery_idempotency_key UNIQUE`. Violação de unicidade no `INSERT` do caso vira repetição idempotente (mesma chave,
mesmo caso, mesmo conteúdo) ou `ConcurrentCaseUpdate`, para o caso de uso recuperar o vencedor com
`findByIdempotencyKey`. Outros erros de integridade ou de SQLite são `SqlitePersistenceException`.

## 3. Leitura

`getById` e `findByIdempotencyKey` leem caso, histórico (`ORDER BY sequence`) e evidências (`ORDER BY sequence`) em uma
transação e reconstroem o agregado com `AnalysisCase.reconstitute`, que valida a cadeia de transições, o estado final,
o vínculo das evidências e a unicidade dos ids. O agregado carregado não tem eventos pendentes.

## 4. Erros

| Exceção | Quando |
|---|---|
| `CaseNotFound` | `getById` sem linha |
| `ConcurrentCaseUpdate` | conflito de versão, de chave de descoberta ou de `event_id` com conteúdo diferente |
| `CorruptAnalysisCaseDataException` | status/tipo/fonte/confiança/evento desconhecidos, UUID não canônico, instante inválido, payload inválido, sequência não contígua, histórico inconsistente |
| `SqlitePersistenceException` | falha operacional de SQLite (I/O, ocupado, constraint inesperada); nunca é tratada como conflito |

Conexões, statements e result sets são fechados com `try-with-resources` em sucesso e falha.

## 5. Representações

- Enums: `name()` como código textual; valor desconhecido nunca vira padrão.
- UUID: `UUID.toString()`; a leitura rejeita formas não canônicas.
- Instantes: `SqliteInstantCodec` (nove casas decimais, UTC).
- `BigDecimal`: texto de `BigDecimal.toString()` (escala preservada, nunca `double`).
- `payload` de evidência, campos `nome=valor` separados por `;` em ordem fixa:
  - `METRIC`: `metric;value;unit;windowStart;windowEnd`
  - `PLAN_SUMMARY`: `nodes` (lista separada por vírgula); `estimatedRows`
  - `RELATION_METADATA`: `relation;estimatedRows;indexCount;compatibleIndexExists`
- `ProtectedEvidenceReference`: `opaqueIdentifier()` em `evidence.protected_reference`; `toString()` continua
  redigido e nunca é persistido. O conteúdo do artefato nunca é resolvido nem gravado.

## 6. Limites conhecidos

- Eventos de domínio já gravados por outro escritor não impedem uma cópia obsoleta idêntica de ser aceita como
  repetição: eles pertencem à fila de saída, não ao estado comparado.
- Eventos de domínio gravados não são relidos como pendentes; a entrega externa é outro card.
