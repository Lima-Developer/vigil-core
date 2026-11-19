# Bootstrap Spring do Vigil

`vigil-bootstrap` é a única camada que depende de Spring Boot. `Main` mantém o CLI de health e casos; o executável
Spring inicia a interface web. O contexto compõe os adapters e casos de uso explicitamente; o target PostgreSQL não
é acessado na inicialização.

## Iniciar localmente

```sh
VIGIL_DATABASE_PATH=/caminho/absoluto/vigil.db \
VIGIL_TARGET_NAME=local-postgres VIGIL_ENVIRONMENT=development \
VIGIL_TARGET_HOST=localhost VIGIL_TARGET_DATABASE=vigil_lab \
  java -jar vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT.jar
```

O diretório SQLite deve existir e ser gravável. Antes das migrações, o startup verifica espaço, permissões e integridade; uma falha impede comandos. As conexões SQLite são abertas e fechadas por operação. Não há servidor HTTP nem recurso persistente que exija fechamento adicional.

As variáveis `VIGIL_TARGET_*`, `VIGIL_ENVIRONMENT`, `VIGIL_DATABASE_PATH` e `VIGIL_STORAGE_MIN_FREE_BYTES` seguem as regras documentadas em `docs/architecture/target-profile.md` e `docs/architecture/sqlite-operacao.md`. O profile `local` provê valores locais para o target; o `test` usa um target fictício e requer um caminho SQLite isolado.

`VIGIL_INFERENCE_ENDPOINT` define o endpoint do adaptador Ollama local (padrão `http://127.0.0.1:11434`). A integração só é considerada ativa quando `vigil.inference.enabled=true` e `vigil.inference.model` estiver definido; nenhuma chamada ocorre no *startup*. `VIGIL_VALIDATION_ARTIFACT` pode apontar para um `result.json` versionado de laboratório. O adaptador de validação recusa resultados que indiquem acesso ao banco monitorado, ausência de índice físico ou ausência de `EXPLAIN ANALYZE`.
