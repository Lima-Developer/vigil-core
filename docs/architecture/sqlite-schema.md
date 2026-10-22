# Schema SQLite e migrations

- **Cards:** VIG-022 (schema base), VIG-023 (evidências, eventos de domínio e repositório)
- **Módulo:** `vigil-adapters`, pacote `com.vigil.adapters.sqlite` (ADR-003 — não há módulo `vigil-adapter-sqlite`)
- **Decisões relacionadas:** ADR-002 (SQLite local em WAL), ADR-003 (monólito modular hexagonal)

Persistência é tradução do domínio, não o domínio. Este documento registra o que precisa sobreviver a um restart
para `AnalysisCase`, como os tipos Java são representados em SQLite e como o schema evolui.

## 1. Mapeamento do agregado

| Domínio | Tabela / coluna | Observações |
|---|---|---|
| `AnalysisCase.caseId()` (`UUID`) | `analysis_case.case_id` | Chave primária textual. |
| `AnalysisCase.createdAt()` (`Instant`) | `analysis_case.created_at` | Formato canônico da seção 3. |
| `AnalysisCase.status()` (`CaseStatus`) | `analysis_case.status` | `DISCOVERED`, `TRIAGED` ou `EVIDENCE_COLLECTED`. |
| `AnalysisCase.version()` | `analysis_case.version` | Revisão persistente para controle otimista (seção 4). |
| `QueryFingerprint.value()` | `analysis_case.query_fingerprint` | Opcional; presente somente junto com a versão do algoritmo. |
| `QueryFingerprint.algorithmVersion()` | `analysis_case.fingerprint_algorithm_version` | String; ausente ou preenchida junto com o fingerprint. |
| chave de `saveDiscovered(caso, idempotencyKey)` | `analysis_case.discovery_idempotency_key` | Opcional e `UNIQUE`; ausente para casos gravados por `save`. |
| `AnalysisCase.events()` (`List<CaseEvent>`) | `case_event` | Uma linha por transição, na ordem da lista. |
| `AnalysisCase.evidences()` (`List<Evidence>`) | `evidence` | Uma linha por evidência, payload seguro em texto (V002). |
| `DomainEvent` pendentes | `domain_event` | Gravados na mesma transação do caso (V002). |

### 1.1 Fingerprint não identifica caso

`AnalysisCaseRepository` e os casos de uso de VIG-021 reservam a idempotência para a chave de descoberta. Por isso,
o fingerprint **não** tem `UNIQUE`: vários casos podem compartilhar o mesmo par fingerprint/algoritmo, e várias linhas
podem ter `discovery_idempotency_key` nula. Somente uma chave de descoberta presente é única.

### 1.2 `case_event` guarda apenas `events()`

`case_event` representa exclusivamente as transições de estado retornadas por `AnalysisCase.events()`:

| `CaseEvent` | Coluna |
|---|---|
| — (posição na lista, a partir de 1) | `sequence` |
| `fromStatus()` | `from_status` |
| `toStatus()` | `to_status` |
| `actor()` | `actor` |
| `reason()` | `reason` |
| `occuredAt()` | `occurred_at` |

- O accessor Java mantém a grafia atual `occuredAt()`; a coluna usa a grafia correta `occurred_at`. O domínio não foi
  alterado neste card.
- `CaseEvent` não tem UUID nem versão própria. Sua identidade persistente é `(case_id, sequence)`. A sequência
  preserva a ordem mesmo quando dois eventos têm o mesmo instante.
- A descoberta atual cria o caso **sem** `CaseEvent`; portanto um caso recém-descoberto não tem linhas em `case_event`.
- `AnalysisCaseDiscovered`, `QueryTriaged` e `EvidenceCollected` implementam `DomainEvent`, são drenados por
  `pullEvents()` e **não** são gravados em `case_event`. Publicação persistente de `DomainEvent` (outbox) exige outro
  desenho.

### 1.3 O que fica de fora

- Conteúdo de artefatos protegidos: `evidence.protected_reference` guarda somente o identificador opaco.
- Telemetria, baselines, diagnósticos e estados futuros da máquina de estados.

O contrato de mapeamento, versão e atomicidade do repositório está em
[analysis-case-persistence.md](analysis-case-persistence.md).

## 2. Schema

Scripts: `vigil-adapters/src/main/resources/db/migration/V000__create_schema_version.sql`,
`V001__create_case_schema.sql` e `V002__create_evidence_and_domain_event_schema.sql`.

### 2.1 `analysis_case`

| Coluna | Tipo | Regras |
|---|---|---|
| `case_id` | `TEXT PRIMARY KEY NOT NULL` | texto (`typeof = 'text'`), não vazio após remover espaços, tabs e quebras de linha |
| `created_at` | `TEXT NOT NULL` | formato de instante (seção 3.2) |
| `status` | `TEXT NOT NULL` | `IN ('DISCOVERED', 'TRIAGED', 'EVIDENCE_COLLECTED')` |
| `version` | `INTEGER NOT NULL DEFAULT 0` | `typeof = 'integer'` e `>= 0` |
| `query_fingerprint` | `TEXT` | nulo ou texto não vazio |
| `fingerprint_algorithm_version` | `TEXT` | nulo ou texto não vazio |
| `discovery_idempotency_key` | `TEXT UNIQUE` | nulo ou texto não vazio |
| — | `CHECK` de tabela | `query_fingerprint` e `fingerprint_algorithm_version` ambos nulos ou ambos preenchidos |

A declaração `INTEGER` não basta para validar a revisão: com afinidade de tipo, SQLite aceita `1.5` ou `'one'` numa
coluna `INTEGER` ([tipagem SQLite](https://www.sqlite.org/datatype3.html)). Por isso `version` e `sequence` também
verificam `typeof(...) = 'integer'`.

### 2.2 `case_event`

| Coluna | Tipo | Regras |
|---|---|---|
| `case_id` | `TEXT NOT NULL` | `REFERENCES analysis_case (case_id) ON DELETE RESTRICT` |
| `sequence` | `INTEGER NOT NULL` | `typeof = 'integer'` e `> 0` |
| `from_status`, `to_status` | `TEXT NOT NULL` | estados conhecidos |
| `actor`, `reason` | `TEXT NOT NULL` | texto não vazio |
| `occurred_at` | `TEXT NOT NULL` | formato de instante |
| — | `PRIMARY KEY (case_id, sequence)` | |

`ON DELETE RESTRICT` preserva a trilha de auditoria: um caso com eventos não pode ser excluído. SQLite aplica RESTRICT
imediatamente, por gatilho interno (código `SQLITE_CONSTRAINT_TRIGGER`, mensagem `FOREIGN KEY constraint failed`).
A máquina de estados continua sendo responsabilidade do domínio; o schema valida apenas que os estados existem.

### 2.3 `evidence` e `domain_event` (V002)

| `evidence` | Regras |
|---|---|
| `evidence_id` | `TEXT PRIMARY KEY`, texto não vazio |
| `case_id` | `REFERENCES analysis_case (case_id) ON DELETE RESTRICT` |
| `sequence` | inteiro `> 0`; `UNIQUE (case_id, sequence)` preserva a ordem de anexação |
| `type`, `source`, `confidence` | códigos conhecidos (`CHECK ... IN`) |
| `captured_at` | formato de instante |
| `collector_version` | texto não vazio |
| `payload` | texto não vazio, formato `nome=valor;...` por tipo (ver analysis-case-persistence.md) |
| `protected_reference` | nulo ou identificador opaco não vazio |

| `domain_event` | Regras |
|---|---|
| `event_id` | `TEXT PRIMARY KEY`, texto não vazio |
| `case_id` | `REFERENCES analysis_case (case_id) ON DELETE RESTRICT` |
| `sequence` | inteiro `> 0`; `UNIQUE (case_id, sequence)` |
| `event_type` | `ANALYSIS_CASE_DISCOVERED`, `QUERY_TRIAGED` ou `EVIDENCE_COLLECTED` |
| `actor`, `event_version` | texto não vazio (`event_version` é a versão do evento, não a revisão do caso) |
| `occurred_at` | formato de instante |
| `evidence_id` | `REFERENCES evidence (evidence_id) ON DELETE RESTRICT`; preenchido se e somente se `EVIDENCE_COLLECTED` |

Índices adicionais: `idx_evidence_captured_at` e `idx_domain_event_occurred_at`.

### 2.4 `schema_version`

| Coluna | Tipo | Regras |
|---|---|---|
| `version` | `INTEGER PRIMARY KEY` | `> 0` (alias de `rowid`, portanto sempre inteiro) |
| `description` | `TEXT NOT NULL` | texto não vazio |
| `checksum` | `TEXT NOT NULL` | SHA-256 em 64 caracteres hexadecimais minúsculos |
| `installed_at` | `TEXT NOT NULL` | formato de instante |

### 2.5 Índices

| Índice | Colunas | Uso previsto |
|---|---|---|
| `idx_analysis_case_status_created_at` | `analysis_case (status, created_at)` | casos por estado em ordem temporal |
| `idx_analysis_case_created_at` | `analysis_case (created_at)` | casos por período |
| `idx_case_event_case_id_occurred_at` | `case_event (case_id, occurred_at)` | linha do tempo de um caso |
| `idx_case_event_occurred_at` | `case_event (occurred_at)` | auditoria por período |

Índices implícitos: a PK de `analysis_case`, o `UNIQUE` de `discovery_idempotency_key` e a PK
`(case_id, sequence)` de `case_event`. A PK composta já cobre consultas e a FK por `case_id`; não existe índice
redundante apenas em `case_id`.

### 2.6 Dados seguros

O schema não tem colunas para SQL bruto, parâmetros, resultados de consultas nem payload genérico do agregado.
`actor`, `reason`, `query_fingerprint` e `discovery_idempotency_key` são metadados sujeitos à política de dados
seguros do Vigil: quem grava é responsável por não colocar dados de negócio neles. **O schema não sanitiza
conteúdo**; constraints relacionais só garantem forma, não ausência de dados sensíveis.

## 3. Representações na fronteira Java

### 3.1 Identificadores

UUIDs são gravados como texto canônico de `UUID.toString()`: 36 caracteres minúsculos com hifens. O futuro
repositório deve ler com `UUID.fromString` e rejeitar valores cuja forma canônica difira do texto armazenado. O schema
exige apenas texto não vazio, sem validar a forma de UUID.

### 3.2 Instantes — `SqliteInstantCodec`

- Formato único: `yyyy-MM-ddTHH:mm:ss.nnnnnnnnnZ`, em UTC, com `Z` literal e **sempre nove casas fracionárias**
  (`2026-09-30T12:00:00.000000000Z`). Nanossegundos são preservados.
- Intervalo suportado: `0001-01-01T00:00:00.000000000Z` a `9999-12-31T23:59:59.999999999Z`
  (`SqliteInstantCodec.MIN`/`MAX`). Nesse intervalo, a ordem textual coincide com a ordem cronológica, o que torna os
  índices sobre `created_at`/`occurred_at` utilizáveis para intervalos.
- `decode` rejeita formas alternativas (sem fração, fração com outra largura, offsets, `z`/`t` minúsculos, espaço,
  espaços nas bordas, ano com sinal ou com mais de quatro dígitos), datas de calendário inválidas (`2026-02-30`,
  `24:00`, segundo `60`) e valores fora do intervalo. `encode` rejeita instantes fora do intervalo.

**O que os `CHECK`s SQL garantem:** somente a forma — texto com dígitos nas posições esperadas, separadores `-`, `T`,
`:`, `.`, nove casas e `Z` final (via `GLOB` ancorado). Eles **não** validam o calendário (`2026-02-30...` passa) nem
o ano `0000`. A validade semântica é garantida por quem grava, usando o codec.

## 4. Três versões distintas

| Conceito | Onde | Significado |
|---|---|---|
| Versão do schema | `schema_version.version` | Migration aplicada. Inteiro positivo, crescente, não necessariamente consecutivo. |
| Revisão do caso | `analysis_case.version` | Revisão persistente para controle otimista. Agregado novo tem `0`; a primeira gravação a torna `1` e `SqliteAnalysisCaseRepository` a incrementa a cada gravação que altera algo. **Não** é derivada de `events().size()`. |
| Versão do evento de domínio | `DomainEvent.version()` | String de versão do contrato do evento publicado. Não é persistida neste schema. |

## 5. Conexões — `SqliteConnectionFactory`

- `openForWrite()` abre a conexão com `transaction_mode=IMMEDIATE`: `setAutoCommit(false)` executa `BEGIN IMMEDIATE`, tomando o lock de escrita antes da leitura da versão. O repositório usa essa conexão em `save`/`saveDiscovered`.

- `new SqliteConnectionFactory(Path)` abre o arquivo local via `jdbc:sqlite:<caminho absoluto>`.
- Antes de devolver a conexão, e ainda em auto-commit (sem transação aberta), a fábrica executa
  `PRAGMA foreign_keys = ON` e confere que `PRAGMA foreign_keys` retorna `1`. Em seguida executa
  `PRAGMA journal_mode = WAL` e confere o retorno `wal`. Foreign keys são configuradas **por conexão** e o pragma é
  ignorado dentro de transações ([SQLite Foreign Key Support](https://www.sqlite.org/foreignkeys.html)); por isso a
  configuração não fica nas migrations.
- Se alguma etapa falhar, a conexão é fechada e a falha original é propagada (falhas no fechamento ficam como
  `suppressed`).
- **Propriedade:** quem chama `open()` é dono da conexão e deve fechá-la (`try-with-resources`). A fábrica não
  mantém pool nem referência às conexões.
- Cada conexão define `busy_timeout` de 5000 ms, para tolerar contenção breve de escrita em vez de falhar de
  imediato com `SQLITE_BUSY`; não torna múltiplos escritores suportados.
- Cada conexão fixa `wal_autocheckpoint=1000` páginas e `synchronous=FULL` (valor `2`), conferindo os valores antes
  de retornar ao chamador. O procedimento de volume, health, backup e restauração está em [sqlite-operacao.md](sqlite-operacao.md).
- Operação conforme ADR-002: um único processo escritor por arquivo e volume local (sem NFS/EFS).

## 6. Migrations — `SqlMigrationRunner`

### 6.1 Catálogo

- Catálogo explícito de `Migration(version, description, resource)`. O catálogo de produção
  (`SqlMigrationRunner.catalog()`) contém `V001 — create case schema` e `V002 — create evidence and domain event schema`. Testes injetam catálogos próprios com
  fixtures em `src/test/resources/db/migration-fixtures`; nenhuma migration fictícia vai para o classpath de produção.
- O construtor ordena o catálogo numericamente e rejeita catálogo vazio e versões duplicadas.
- Antes de tocar no banco, `migrate` carrega **todos** os recursos (inclusive V000), rejeita recurso ausente ou bytes
  que não sejam UTF-8 válido, divide os scripts e calcula SHA-256 sobre os bytes do recurso.

### 6.2 Histórico

- O histórico válido é um **prefixo** do catálogo ordenado: as versões gravadas, em ordem, devem coincidir com as
  primeiras entradas do catálogo, sem exigir números consecutivos (V001, V003 é válido).
- São rejeitados, sem reparo: versão maior que a última suportada, versão ausente do catálogo ou fora de ordem,
  descrição ou checksum divergentes, `installed_at` fora do formato canônico, `schema_version` sem entradas e
  `schema_version` sem as colunas esperadas.
- As migrations pendentes são as entradas do catálogo após o prefixo, aplicadas em ordem numérica.
- `installed_at` vem do port `com.vigil.application.port.out.interfaces.Clock`, o que torna os testes determinísticos.

### 6.3 Transações

- Controle transacional exclusivamente por JDBC (`setAutoCommit(false)`, `commit`, `rollback`). Scripts **não** podem
  conter `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT` nem `RELEASE`
  ([transações SQLite](https://www.sqlite.org/lang_transaction.html)); o executor rejeita esses scripts ao carregá-los.
- Cada migration é confirmada junto com sua linha em `schema_version`, no mesmo `commit`. Migrations anteriores já
  confirmadas permanecem se uma posterior falhar.
- Primeira execução: `V000__create_schema_version.sql` roda na **mesma transação** que a primeira migration funcional.
  Se ela falhar, `schema_version` também desaparece e o banco volta a ficar vazio. V000 nunca é registrada no histórico;
  o histórico começa em V001.
- Em falha: `rollback` explícito, `MigrationException` com a causa original, e falhas de rollback ou restauração de
  auto-commit anexadas como `suppressed`. Se o próprio rollback falhar, o auto-commit **não** é restaurado (isso
  confirmaria a transação parcial); a conexão deve ser descartada, e fechá-la faz SQLite descartar a transação.
- Tabelas funcionais não usam `IF NOT EXISTS`: um banco com objetos inesperados e sem histórico falha em vez de
  mascarar a divergência.
- Concorrência entre executores não é suportada: a migração ocorre na inicialização do único processo escritor.

### 6.4 Formato dos scripts

- Cada segmento contém **exatamente uma instrução completa**. Segmentos são separados por uma linha que contém apenas
  `-- vigil:statement-end` (espaços nas bordas são ignorados). Como é um comentário SQL, o arquivo continua válido para
  outras ferramentas.
- Ponto e vírgula e quebras de linha dentro de literais são seguros, porque o executor não divide por `;`.
- Limites conhecidos: o delimitador é reconhecido mesmo dentro de um literal multilinha se ocupar sozinho uma linha;
  comentários de bloco `/* */` não são interpretados na detecção de segmentos vazios ou de controle transacional; e o
  driver compila somente a primeira instrução de cada segmento, então uma segunda instrução no mesmo segmento seria
  ignorada. Os testes cobrem literal com `;` e literal com o texto do delimitador fora de linha isolada.

## 7. Inicialização (composição futura)

As migrations **não** estão conectadas ao `Main` atual. A composição concreta pertence a `vigil-bootstrap` e deve
executar o runner antes de disponibilizar o futuro repositório SQLite:

```java
var factory = new SqliteConnectionFactory(databaseFile);
try (var connection = factory.open()) {
    new SqlMigrationRunner(new SystemClockAdapter()).migrate(connection);
}
// somente depois: new SqliteAnalysisCaseRepository(factory)
```

Uma `MigrationException` na inicialização deve impedir o start da aplicação; ela não deve ser convertida em resposta
para clientes.

## 8. Testes e mutação

- `SqliteSchemaTest`, `SqlMigrationRunnerTest` e `SqliteConnectionFactoryTest` usam SQLite/Xerial real em arquivos
  de `@TempDir`, fechando e reabrindo o arquivo para provar persistência e WAL. `SqliteInstantCodecTest` cobre o
  codec.
- PIT está configurado apenas em `vigil-adapters`, com alvos `com.vigil.adapters.sqlite.*` e `failWhenNoMutations`
  ativo. Os demais módulos pulam PIT explicitamente, então um relatório vazio no adapter falha o build.
- PIT não altera arquivos `.sql`. A validação do schema vem da inspeção (`pragma_table_info`,
  `pragma_foreign_key_list`, `pragma_index_list`/`pragma_index_info`) e dos inserts inválidos que conferem o código de
  erro esperado. Remover `FK`, `UNIQUE`, `CHECK` ou índices nos scripts deve quebrar esses testes; cobertura Java não
  substitui essa verificação.
