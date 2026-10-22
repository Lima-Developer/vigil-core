# Preflight PostgreSQL (VIG-033)

O preflight lê configurações e catálogos por uma conexão somente leitura de VIG-032. O relatório traz códigos estáveis, versão numérica, banco, instante da checagem e disponibilidades; não traz SQL observado, URL nem credenciais. A probe não executa DDL, `SET`, nem lê `pg_stat_statements.query`.

| Capacidade | Mínimo/condição | Falha | Remediação |
|---|---|---|---|
| CONNECTION | Conexão bem-sucedida | BLOCKED | Verificar conectividade e credenciais com o administrador. |
| SERVER_VERSION | `server_version_num >= 140000` | BLOCKED | Usar PostgreSQL 14 ou superior. |
| PG_STAT_STATEMENTS_AVAILABLE | Presente em `pg_available_extensions` | BLOCKED | DBA instala pacote contrib ou ajusta imagem PostgreSQL. |
| PG_STAT_STATEMENTS_INSTALLED | Presente em `pg_extension` | BLOCKED | DBA executa `CREATE EXTENSION pg_stat_statements;`. |
| PG_STAT_STATEMENTS_PRELOADED | Presente em `shared_preload_libraries` | BLOCKED | DBA configura preload e reinicia servidor. |
| STATS_READABLE | Superuser ou membro de `pg_read_all_stats` | BLOCKED | DBA concede `pg_read_all_stats` ao papel do Vigil. Falha de acesso ao catálogo também usa este código. |
| COMPUTE_QUERY_ID | `on` ou `auto` com preload | BLOCKED | DBA configura `compute_query_id = auto` e recarrega configuração. Valor desconhecido bloqueia. |
| TRACK_IO_TIMING | `on` | DEGRADED | DBA configura `track_io_timing = on` e recarrega configuração. `off` ou desconhecido significa métrica de I/O indisponível, nunca zero. |

Qualquer falha obrigatória prevalece sobre a ausência da métrica opcional. Sem falha obrigatória, a ausência de `track_io_timing` gera `DEGRADED`; todas disponíveis geram `READY`. Os resultados seguem a ordem de `CapabilityCode`. Quando a conexão falha, versão e banco ficam ausentes e o relatório contém apenas `CONNECTION`. A checagem por papel pode rejeitar permissões equivalentes concedidas de outra forma.

Exemplos: ambiente completo → `READY`; `track_io_timing=off` → `DEGRADED`, `TRACK_IO_TIMING=MISSING`; extensão disponível mas não criada → `BLOCKED`, `PG_STAT_STATEMENTS_INSTALLED=MISSING`.
