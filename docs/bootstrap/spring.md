# Bootstrap Spring do Vigil

`vigil-bootstrap` é a única camada que depende de Spring Boot. Domínio e aplicação continuam testáveis sem contexto Spring. A CLI anterior permanece em `com.vigil.bootstrap.Main`; a aplicação web inicia por `com.vigil.bootstrap.VigilApplication`.

## Iniciar localmente

```sh
VIGIL_DATABASE_PATH=/caminho/absoluto/vigil.db \
  java -jar vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

O diretório do arquivo SQLite precisa existir e ser gravável. No início, o preflight verifica espaço, permissões e integridade antes das migrações; falhas impedem o contexto de subir. O encerramento HTTP usa o shutdown gracioso do Spring.

O profile `local` aceita `VIGIL_TARGET_NAME`, `VIGIL_ENVIRONMENT`, `VIGIL_TARGET_HOST`, `VIGIL_TARGET_DATABASE`, `VIGIL_DATABASE_PATH` e `VIGIL_STORAGE_MIN_FREE_BYTES`. Para outros ambientes, configure propriedades `vigil.target.*`, `vigil.sqlite.*` e `vigil.inference.*` por configuração externa. A inferência está apenas mapeada e não inicia serviços neste card.

O profile `test` é usado no teste de contexto com arquivo SQLite temporário; não acessa o PostgreSQL monitorado. A aplicação ainda não expõe a API do caso único, que pertence ao VIG-087.
