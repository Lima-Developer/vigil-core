# Primeiro fluxo persistido: `VigilCli`

- **Card:** VIG-025
- **Código:** `vigil-bootstrap`, `com.vigil.bootstrap.VigilCli` (API pública) e `CaseFlow` (composition root)

`VigilCli.run(String[] args, Map<String,String> env, PrintStream out, PrintStream err)` devolve o código de saída sem
encerrar a JVM; `Main.main` apenas delega a ela. Sem argumentos, mantém o health check.

## Sintaxe

```
case discover
case triage --case <uuid> --reason <motivo>
case show   --case <uuid>
```

- As opções podem vir em qualquer ordem, cada uma uma só vez, com exatamente um argumento (use aspas em motivos com espaços).
- O arquivo SQLite vem de `VIGIL_DATABASE_PATH`; as migrações são aplicadas a cada comando e as conexões são fechadas ao final, inclusive em falha.
- `case discover` observa sempre a mesma consulta fixa (`SELECT`, `PG_STAT_STATEMENTS`, janela e métricas constantes) com chave de idempotência constante: repetir devolve o mesmo caso com `created=false`.
- O caso é identificado pelo `uuid` impresso por `discover`, usado em `--case`. A triagem é atribuída ao ator `vigil-cli`.

## Saída (stdout)

| Comando | Linhas |
|---|---|
| `discover` | `case=<uuid> created=<true\|false>` |
| `triage` | `case=<uuid> status=TRIAGED` |
| `show` | `case=<uuid> status=<status> version=<n>`, uma linha `history=<DE>-><PARA> reason=<texto>` por transição e uma linha `event=<tipo>` por evento de domínio persistido, em ordem |

`show` lê eventos pela porta `AnalysisCaseAuditReader`, que expõe só tipo e metadados (ator, instante, versão); nunca payload nem SQL.

## Códigos de saída

| Código | Significado |
|---|---|
| `0` | sucesso |
| `2` | uso ou configuração inválidos: comando/opção desconhecida, ausente ou repetida, uuid malformado, motivo em branco, `VIGIL_DATABASE_PATH` ausente |
| `1` | falha operacional: caso inexistente, caso não triável, atualização concorrente, armazenamento indisponível ou corrompido |

Erros vão para stderr com texto fixo do bootstrap; mensagens do driver, SQL, stack traces e o motivo informado nunca são impressos.

## Eventos de domínio

`DiscoverObservedQueryUseCase` grava `AnalysisCaseDiscovered` (ator de sistema `vigil-system`) e
`TriageAnalysisCaseUseCase` grava `QueryTriaged` (ator do comando). Ambos usam `DomainEventMetadata` com id próprio
vindo de `IdGenerator`, instante do `Clock` e versão de evento estável `"1"`, independente de `AnalysisCase.version()`.
