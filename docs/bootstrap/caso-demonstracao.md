# Avaliação visual de um caso

A aplicação web local oferece uma página de um único caso. Identidade, estado, histórico e eventos de domínio ficam em SQLite; a triagem passa pelo caso de uso e exige motivo. O target PostgreSQL é acessado somente para `EXPLAIN (FORMAT JSON)` pela política de leitura de VIG-036. O SQL bruto não entra na resposta nem no SQLite.

## Execução

```sh
./mvnw verify
VIGIL_DATABASE_PATH=/caminho/absoluto/vigil-demo.db \
VIGIL_TARGET_USER=usuario_somente_leitura \
VIGIL_PG_PASSWORD_FILE=/caminho/para/segredo \
VIGIL_DEMO_SELECT='SELECT 1' \
  java -jar vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=local --server.port=8080
```

Abra `http://127.0.0.1:8080/`, clique em **Iniciar avaliação do caso** e registre uma triagem justificada. Em cada ensaio da defesa, use um **novo nome de arquivo SQLite** para repetir o fluxo desde o início; assim o histórico anterior permanece intacto. O serviço escuta apenas `127.0.0.1`. O profile local aceita `VIGIL_TARGET_HOST`, `VIGIL_TARGET_DATABASE`, `VIGIL_TARGET_NAME`, `VIGIL_ENVIRONMENT` e `VIGIL_DATABASE_PATH`. A conexão exige exatamente uma entre `VIGIL_PG_PASSWORD` e `VIGIL_PG_PASSWORD_FILE`; para CA personalizada, passe `--vigil.demo.ssl-root-cert=/caminho/ca.crt`. O modo TLS padrão é `verify-full`.

A consulta inicial e suas métricas são **fixture de laboratório**, explicitamente marcada na página. O caso e a auditoria são persistidos. Sem usuário/target configurado, a página mostra um plano de **fixture**, explicitamente identificado como não coletado; com eles, mostra nós, linhas e custo do EXPLAIN **estimados**, nunca ganho medido. Não há baseline. `?demo=1` abre um protótipo inteiramente sintético, cuja triagem não é persistida.

A versão demonstrativa não recomenda índices nem altera o target monitorado. Para afirmar avaliação de dados reais,
habilite o coletor com `VIGIL_TELEMETRY_ENABLED=true`, o usuário somente leitura e a mesma política de senha. Sem
adapter oficial de provedor, `freeLocalStorage` permanece indisponível por desenho.

## Roteiro de apresentação — 8 a 10 minutos

1. **Visão Geral (1 min):** destaque dez queries complexas, 20/20 validações, escala 3, dois seeds, sete repetições e
   280 planos. Explique que melhoria não é pressuposta: há regressões e um controle idêntico.
2. **Caso Q02 (2 min):** mostre as subqueries correlacionadas, a pré-agregação manual, os dois planos e as sete
   amostras. Aponte os riscos sem generalizar o ganho.
3. **Curadoria IA (1 min):** mostre que o modelo recomendou a candidata, mas recebeu somente corpus sintético e não
   pôde gerar/executar uma terceira query. A decisão humana continua separada.
4. **Caso Q07 (1 min):** mostre a candidata rejeitada pela IA após regressão nos dois seeds. Esse contraexemplo
   sustenta a necessidade de validar, não apenas reescrever.
5. **Uso de IA (1 min):** apresente input/cache/output reportados, reasoning, duração e projeções; esclareça que
   custo da assinatura não foi reportado e que Claude é apenas projeção com os mesmos tokens.
6. **Telemetria (1 min):** com o coletor desligado, mostre estados `unsupported` e volume local real. Se houver target
   de laboratório configurado, mostre deltas e sessões persistidos, pausa e horário da última leitura.
7. **Auditoria e triagem (2 min):** crie um caso novo, registre justificativa e mostre a linha do tempo SQLite. Feche
   com os limites: sandbox sintético, validade externa, privilégio mínimo e parecer humano obrigatório.

## Modo offline identificado

Use `http://127.0.0.1:8080/?demo=1#overview`. Corpus, planos compactados, curadorias e usage já estão embutidos no
JAR; não exigem rede durante a defesa. A prévia de Telemetria é sintética e recebe aviso próprio. Mantenha também os
relatórios em `docs/experiments/` para responder perguntas sem depender da interface.
