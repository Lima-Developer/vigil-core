# Bootstrap Spring do Vigil

`vigil-bootstrap` é a única camada que depende de Spring Boot. A entrada `Main` e o executável Spring usam o mesmo CLI. O contexto compõe os adapters e casos de uso explicitamente; o target PostgreSQL não é acessado na inicialização.

## Iniciar localmente

```sh
VIGIL_DATABASE_PATH=/caminho/absoluto/vigil.db \
VIGIL_TARGET_NAME=local-postgres VIGIL_ENVIRONMENT=development \
VIGIL_TARGET_HOST=localhost VIGIL_TARGET_DATABASE=vigil_lab \
  java -jar vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT.jar
```

O diretório SQLite deve existir e ser gravável. Antes das migrações, o startup verifica espaço, permissões e integridade; uma falha impede comandos. As conexões SQLite são abertas e fechadas por operação. Não há servidor HTTP nem recurso persistente que exija fechamento adicional.

As variáveis `VIGIL_TARGET_*`, `VIGIL_ENVIRONMENT`, `VIGIL_DATABASE_PATH` e `VIGIL_STORAGE_MIN_FREE_BYTES` seguem as regras documentadas em `docs/architecture/target-profile.md` e `docs/architecture/sqlite-operacao.md`. O profile `local` provê valores locais para o target; o `test` usa um target fictício e requer um caminho SQLite isolado. A inferência possui apenas propriedades tipadas neste card, sem integração externa.
