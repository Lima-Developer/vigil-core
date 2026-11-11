# Avaliação visual de um caso

A aplicação web local oferece uma página de um único caso. Identidade, estado, histórico e eventos de domínio ficam em SQLite; a triagem passa pelo caso de uso e exige motivo. O target PostgreSQL é acessado somente para `EXPLAIN (FORMAT JSON)` pela política de leitura de VIG-036. O SQL bruto não entra na resposta nem no SQLite.

## Execução

```sh
./mvnw verify
VIGIL_DATABASE_PATH=/caminho/absoluto/vigil-demo.db \
VIGIL_TARGET_USER=usuario_somente_leitura \
VIGIL_DEMO_SELECT='SELECT 1' \
  java -jar vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=8080
```

Abra `http://127.0.0.1:8080/`, clique em **Iniciar avaliação do caso** e registre uma triagem justificada. O serviço escuta apenas `127.0.0.1`. O profile local aceita `VIGIL_TARGET_HOST`, `VIGIL_TARGET_DATABASE`, `VIGIL_TARGET_NAME`, `VIGIL_ENVIRONMENT`, `VIGIL_DATABASE_PATH` e a credencial PostgreSQL suportada pelo adapter.

A consulta inicial e suas métricas são **fixture de laboratório**, explicitamente marcada na página. O caso e a auditoria são persistidos. Sem usuário/target configurado, a página informa que EXPLAIN está indisponível; com eles, mostra nós, linhas e custo **estimados**, nunca ganho medido. Não há baseline. `?demo=1` abre um protótipo inteiramente sintético, cuja triagem não é persistida.

A versão demonstrativa não recomenda índices nem altera o target monitorado. Para afirmar avaliação de dados reais, substitua a fixture por telemetria coletada e persistida.
