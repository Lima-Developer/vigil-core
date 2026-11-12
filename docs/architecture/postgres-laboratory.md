# Laboratório PostgreSQL (Testcontainers)

- **Card:** VIG-030
- **Módulo:** `vigil-adapters`, código somente em `src/test`, pacote `com.vigil.adapters.postgres.lab` (ADR-003)
- **Escopo:** fixture reproduzível para testar diagnóstico. Nada aqui é código de produção nem coleta real.

O diagnóstico só pode ser testado se o cenário for repetível. O laboratório sobe um PostgreSQL fixado, habilita
`pg_stat_statements`, carrega dados sintéticos determinísticos e executa um workload conhecido.

## 1. Versão e justificativa

| Item | Valor |
|---|---|
| Imagem | `postgres:16.4-bookworm` (constante única `PostgresLaboratory.IMAGE`) |
| Digest observado | `postgres@sha256:e62fbf9d3e2b49816a32c400ed2dba83e3b361e6833e624024309c35d334b412` |
| Testcontainers | 1.21.4 (linha 1.x; não misturar com a 2.x, que renomeou módulos) |
| Driver JDBC | `org.postgresql:postgresql` 42.7.7 |

- Tag fixa, nunca `latest`: o planner e as estatísticas variam entre versões.
- A tag pode sair do registry; o digest acima é o registro para reconstruir a imagem. O código usa a tag.
- Dependências em escopo `test` apenas; `vigil-domain` e `vigil-application` não ganham dependência.

## 2. Inicialização

O container sobe com `-c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all`. Sem o
preload, `CREATE EXTENSION` funciona, mas as estatísticas ficam vazias. Sequência em `PostgresLaboratory.initialize()`:

1. `CREATE EXTENSION IF NOT EXISTS pg_stat_statements` e `pg_stat_statements_reset()`.
2. Schema de `src/test/resources/lab/postgres/schema.sql`.
3. Carga em lotes de 5.000 (`addBatch`/`executeBatch`, commit por lote).
4. `ANALYZE customers; ANALYZE orders;`.
5. Workload, 50 execuções de cada query.

Usuário, senha e URL JDBC vêm de `getUsername`, `getPassword` e `getJdbcUrl` do container; não há credencial literal.

## 3. Schema

| Tabela | Colunas |
|---|---|
| `customers` | `id` identity PK, `region`, `segment`, `created_at timestamptz` |
| `orders` | `id` identity PK, `customer_id` FK para `customers.id`, `status`, `total_cents integer`, `placed_at timestamptz` |

Único índice além das PKs: `orders_customer_id_idx` em `orders(customer_id)`. **Não há** índice em `orders.status` nem em
`orders.placed_at`; é isso que torna P1 e P2 problemáticas. Os ids são inseridos explicitamente (identity
`BY DEFAULT`); a sequência não é reajustada, pois o laboratório não insere novas linhas.

## 4. Seed e volume

| Item | Valor |
|---|---|
| Seed padrão | 42 (`SplittableRandom`; sem `Random` global) |
| `customers` | 20.000 |
| `orders` | 300.000 |
| Lote | 5.000 |
| Datas | derivadas de um epoch fixo (`2024-01-01T00:00:00Z`); sem `Instant.now()` |

- Valores textuais vêm de vocabulários fechados: regiões (`NORTH`, `SOUTH`, `EAST`, `WEST`, `CENTRAL`), segmentos
  (`RETAIL`, `WHOLESALE`, `ENTERPRISE`, `STARTUP`) e status (`PENDING`, `COMPLETED`, `SHIPPED`, `CANCELLED`).
  Nenhum dado real; testes garantem ausência de `@` e de 11 dígitos consecutivos.
- `PENDING` ≈ 0,5% dos pedidos. O cliente 1 tem exatamente 15 pedidos (usado por H).
- `LaboratoryDataset.sha256()` identifica o conteúdo; mesmo seed gera o mesmo hash, seed 43 gera outro.
- Após a carga, `count(*)` e `sum(total_cents)` do banco são comparados aos valores calculados pelo gerador.

## 5. Queries

| Id | Papel | SQL (forma parametrizada usada no workload) |
|---|---|---|
| H | saudável | `SELECT id, status, total_cents FROM orders WHERE customer_id = ?` (cliente 1) |
| P1 | problemática | `SELECT id, total_cents FROM orders WHERE status = ?` (`'PENDING'`) |
| P2 | problemática | `SELECT id, customer_id FROM orders ORDER BY placed_at DESC LIMIT 50` |

O `EXPLAIN` usa as formas com literais (`customer_id = 1`, `status = 'PENDING'`), para evitar planos genéricos de
prepared statement. Em `pg_stat_statements` as entradas são localizadas pelo **texto normalizado** (`$1`), não por
`queryid`, e devem ter `calls >= 50`. A leitura desses contadores em produção está descrita em
[postgres-coleta-pg-stat-statements.md](postgres-coleta-pg-stat-statements.md).

## 6. Plano esperado e tolerância

`EXPLAIN` em formato TEXT, após `ANALYZE`. Verifica-se apenas a **presença de nós**; o plano completo, custos e linhas
estimadas não são comparados, pois não são estáveis byte a byte entre versões ou execuções.

| Query | Deve conter | Não deve conter |
|---|---|---|
| H | `orders_customer_id_idx` | `Seq Scan on orders` |
| P1 | `Seq Scan on orders` | — |
| P2 | `Sort` e `Seq Scan on orders` | — |

## 7. Pré-requisitos e execução

- Docker (ou compatível, como Rancher Desktop) com daemon ativo; `./mvnw -B -q verify` roda `PostgresLaboratoryIT`
  via Failsafe, sem passo manual de banco.
- Ambientes com socket não padrão precisam de `DOCKER_HOST`; sem Docker as ITs falham (não são ignoradas).
- Tempo medido: a IT leva cerca de 6 s após o container baixado; o `verify` completo ficou em ~14 s.
- Pitest (`pitest-maven` 1.17.4) só muta `target/classes`; o laboratório vive em `src/test/java` e, portanto, **não**
  entra no comando oficial de mutação (que cobre apenas `com.vigil.adapters.sqlite.*`). Mover o laboratório para
  `src/main` quebraria o critério de `src/main` intocado e foi descartado.
- A qualidade dos testes do laboratório foi medida fora do repositório, com o mesmo motor pitest apontando
  `codePaths` para `target/test-classes`: 44/44 mutantes mortos em `LaboratoryDataset`, `LaboratoryWorkload` e `Batcher`;
  `PostgresLaboratory` só é exercitado pela IT e fica fora do loop. Os `*MutationTest` do laboratório guardam esse nível.

## 8. Decisões em aberto

O acervo complementar para a defesa está em [tools/complex-query-lab](../../tools/complex-query-lab/README.md):
10 cenários complexos, dataset próprio e runner isolado. Ele não substitui as fixtures H/P1/P2 nem altera as ITs do VIG-030.

- **Mutação do laboratório:** sem suporte do `pitest-maven` a código mutável em `target/test-classes`, a cobertura
  de mutação do lab depende de um driver externo; decidir se vale um módulo dedicado ou aceitar a medição manual.
- **Docker em CI:** o repositório não tem pipeline (`.github/` ausente). A política (runner com Docker, cache de
  imagem, digest fixado) fica para decisão do time; este card não cria workflow.
- **VIG-003:** dependência do card sem rastro no git ou nos docs; premissa vinda apenas do board.
