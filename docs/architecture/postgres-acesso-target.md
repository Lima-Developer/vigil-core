# Acesso ao target PostgreSQL com menor privilégio

- **Card:** VIG-032
- **Código:** `vigil-adapters`, pacote `com.vigil.adapters.postgres` (ADR-003); erro público em `vigil-application`
  (`com.vigil.application.exception.TargetConnectionFailure`)
- **Escopo:** conexão JDBC, limites de tempo e classificação de falhas. Coleta de telemetria fica fora.

`java.sql.Connection` nunca sai do adapter: `PostgresConnectionFactory` é package-private e a regra ArchUnit
`shouldKeepApplicationFreeOfJdbc` impede `java.sql`/`javax.sql` em `com.vigil.application..`. O único erro que cruza a
fronteira é `TargetConnectionFailure` (motivo, nome do target, SQLSTATE opcional). A mensagem não traz URL, host,
usuário nem senha; a causa original fica em `getCause()` só para log interno.

## 1. Defesa em camadas

`readOnly` na API JDBC e `default_transaction_read_only=on` são defesa adicional: o próprio usuário pode executar
`SET default_transaction_read_only = off`. A barreira real é o privilégio. A IT prova as duas camadas:
`CREATE TABLE`/`INSERT` falham com `25006` na sessão padrão e com `42501` depois do `SET ... = off`.

## 2. Matriz de grants (`vigil_collector`)

Script de laboratório: `src/test/resources/postgres/vigil-lab-role.sql` (a senha entra por variável do psql, nunca
literal). Atributos: `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS`.

| Objeto | Privilégio | Origem | Motivo |
|---|---|---|---|
| Database alvo | `CONNECT` | `GRANT CONNECT` | abrir sessão |
| `pg_stat_database`, `pg_stat_user_tables`, `pg_stat_activity` | leitura | views públicas + `pg_monitor` | telemetria |
| `pg_stat_statements` | leitura, inclusive texto da query | `pg_monitor` | consultas observadas |
| Catálogos (`pg_class`, `pg_index`, ...) | leitura | `PUBLIC` | metadados de relação |
| Tabelas e schemas da aplicação | **nenhum** | — | o Vigil não lê dados de negócio |
| `public` (CREATE) | **nenhum** | `REVOKE` explícito no script (padrão apenas no 15+) | impede DDL |

- `pg_monitor` é concedido com `WITH INHERIT TRUE` no PostgreSQL 16+, mantendo `NOINHERIT` no restante. Em 14 e 15 não
  existe a opção por concessão: o script (via `\if` do psql) concede `pg_monitor` e aplica `ALTER ROLE ... INHERIT`,
  aceitável porque o papel não tem outra pertinência. Sem isso o `NOINHERIT` anularia o `pg_monitor`.
- O script faz `REVOKE CREATE ON SCHEMA public FROM PUBLIC` em todas as versões: no 14 o `PUBLIC` ainda cria objetos
  em `public`, e a barreira de privilégio (42501) não pode depender do `default_transaction_read_only`.
- A IT roda por padrão em **16.4** (`PostgresLaboratory.IMAGE`) e é parametrizável por `-Dvigil.postgres.image`
  (14, 15, 16, 17; 18 por validar).
- A IT afirma `rolsuper`, `rolcreaterole`, `rolcreatedb`, `rolreplication`, `rolbypassrls` falsos (e `rolinherit` falso no 16+).
  Em Aurora/RDS o usuário pode herdar `rds_superuser`; essa checagem deve ser repetida ali.
- Se um pooler intermediário descartar o parâmetro `options`, o servidor não aplica os limites. Por isso a fábrica
  confirma cada valor com `SHOW` e não assume.

### Degradação de `pg_stats`

`pg_stats` só mostra colunas de tabelas em que o papel tem `SELECT`. Sem grant de tabela, a view vem vazia (a IT
verifica contagem 0 para `orders`). Isso é **capacidade indisponível**, não falha: o consumidor deve reportar
"estatísticas de coluna indisponíveis" e não tratar como erro de conexão.

## 3. Sessão e timeouts

| Parâmetro | Valor | Onde é aplicado |
|---|---|---|
| `connectTimeout` | 5 s (`TargetProfile`) | driver |
| `socketTimeout` | 10 s | driver; sempre **maior** que `statement_timeout` |
| `statement_timeout` | 5000 ms | `options=-c ...` no startup |
| `lock_timeout` | 200 ms | `options=-c ...` no startup |
| `default_transaction_read_only` | `on` | `options=-c ...` no startup |
| `ApplicationName` | `vigil` | driver |

`PostgresTargetSettings` valida timeouts de pelo menos 1 ms e `socketTimeout > statementTimeout`. Depois de conectar,
a fábrica chama `setAutoCommit(true)` e `setReadOnly(true)` e confirma com `SHOW` os valores na forma normalizada do
servidor (`5s`, `200ms`, `on`, `vigil`). Qualquer divergência fecha a conexão e resulta em `UNKNOWN`.

TLS: somente `verify-ca`/`verify-full` (`TargetSslMode`). `sslrootcert` é opcional; sem ele vale o padrão do driver (`~/.postgresql/root.crt`); se o arquivo não existir ou não puder ser lido, a falha é `TLS_FAILED` sem retry, e a conexão nunca cai para um modo sem verificação.

## 4. Classificação e retry

| Condição | Motivo | Retry |
|---|---|---|
| `SSLException` na cadeia (ou mensagem do pgjdbc de hostname não verificado / servidor sem SSL) | `TLS_FAILED` | não |
| `SocketTimeoutException`, `ConnectException`, `NoRouteToHostException`, `UnknownHostException` | `UNREACHABLE` | sim |
| SQLSTATE `08xxx`, `57P01`, `57P03` | `UNREACHABLE` | sim |
| `28P01`, `28000` | `AUTHENTICATION_FAILED` | não |
| `42501` | `AUTHORIZATION_DENIED` | não |
| `57014` (statement), `55P03` (lock) | `TIMEOUT` | não |
| nulo ou desconhecido | `UNKNOWN` | não |

A cadeia (`getCause` e `getNextException`) é percorrida uma vez por nó, com proteção contra ciclos. TLS tem
precedência sobre `08xxx`. O pgjdbc não envolve a falha de hostname em `SSLException`; por isso há um fallback pela
mensagem em inglês. Com mensagem localizada o caso cai em `UNREACHABLE`, que continua limitado a 3 tentativas.

Retry apenas na **abertura** da conexão: no máximo 2 retentativas, com espera de 200 ms e 800 ms (`Sleeper`
injetável). Esgotadas, lança `TargetConnectionFailure(UNREACHABLE)`. Statements nunca são repetidos.

## 5. Segredos

A senha vem de `VIGIL_PG_PASSWORD` ou do arquivo apontado por `VIGIL_PG_PASSWORD_FILE` (exatamente uma das duas;
`PostgresPasswordSource`), como `Supplier<char[]>`, fora do record de settings e do `toString`. A fábrica zera o
`char[]` e limpa o `Properties` após cada tentativa. Limite conhecido: o driver exige a senha como `String`, que não
pode ser zerada. Nenhuma propriedade nem URL é logada.

## 6. Testes

- Unitários (alimentam o PIT): settings, classificador parametrizado, fábrica com opener/sleeper falsos, fonte de senha.
- `PostgresConnectionFactoryIT` (Docker obrigatório, PostgreSQL 16.4 do laboratório): TLS real com CA e certificado
  gerados dentro do container na subida (nenhuma chave no repositório; só o certificado público da CA é copiado
  para o host), `SHOW` da sessão, telemetria, `25006`/`42501`, `57014` em ~5 s, `55P03`, senha errada,
  `CONNECT` revogado, porta fechada (3 tentativas, esperas 200/800 ms) e hostname divergente (`TLS_FAILED`).
- Mutação: `com.vigil.adapters.postgres.Postgres*` (o pacote `lab` fica de fora). Settings e classificador sem
  sobreviventes. Sobreviventes justificados: o `Thread.sleep` do `Sleeper` padrão (sem cobertura unitária; as ITs
  injetam o sleeper) e dois `Arrays.fill` de buffers internos em `PostgresPasswordSource` (efeito não observável).

## 7. Metadados de schema (VIG-035)

- **Código:** `com.vigil.adapters.postgres.schema`. A fachada pública é `PostgresSchemaCatalogCollector`; o restante é
  package-private. O contrato de domínio (`SchemaBlueprint`, `RelationType`, ...) não conhece JDBC.
- **Entrada:** `RelationSelector(schema, nome)` reais, só dentro da fronteira confiável (`toString` não os imprime).
  Somente relações pedidas e dos tipos `r`, `m`, `p`, `f` são coletadas.
- **SQL:** quatro consultas constantes e parametrizadas, só em `pg_class`, `pg_namespace`, `pg_attribute`, `pg_type`
  (`format_type`), `pg_constraint`, `pg_index` e `pg_am`. Não há `COUNT(*)`, `SELECT` em tabela do usuário nem DDL; o
  papel do laboratório (sem grant em tabela) basta. As consultas rodam em uma transação `REPEATABLE READ`
  somente leitura, com timeout de 10 s, e sofrem rollback.
- **Privacidade:** `indpred`, `pg_get_indexdef` e `pg_get_constraintdef` nunca são consultados; o predicado vira
  `partial`. Tipos fora de `pg_catalog` viram `user_defined`. Exceções carregam apenas o SQLSTATE.
- **Pseudônimos:** HMAC-SHA256 com sal de 32 bytes sorteado por coleta (injetável), formato `relation_<hex32>` para a
  relação (`SafeRelationReference`) e `schema_`/`column_`/`index_<hex32>` (`SafeCatalogReference`) para o restante.
- **Índices:** identidade por `indexrelid`. O índice que o PostgreSQL cria em cada partição é uma entrada própria do
  catálogo e aparece uma vez na relação a que pertence.
- **Falhas:** `42501` vira blueprint explicitamente incompleto (`CATALOG_PRIVILEGE_DENIED`) com source e instante;
  relação pedida que não retorna vira `RELATION_NOT_VISIBLE`. Qualquer outro `SQLException` é
  `SchemaCollectionException` (sem mensagem nem causa do driver).
- **Limites:** `reltuples`/`relpages` são estimativas; foreign tables são descritas, nunca lidas. Persistência: o
  `RelationMetadataEvidencePayload` e o `EvidencePayloadCodec` não mudaram; o blueprint ainda não é persistido.
- **Testes:** unitários sem Docker (pseudonimização, SQL, montagem, leitura JDBC roteirizada) e
  `PostgresSchemaCatalogCollectorIT` com `schema-catalog-fixture.sql` (PG 14–17 via `-Dvigil.postgres.image`).
