# TargetProfile — alvo único monitorado

O Vigil conhece exatamente um alvo PostgreSQL. `TargetProfile` (`vigil-application`, pacote `configuration`) guarda
somente configuração pública; `TargetCapabilities` (pacote `target`) guarda o resultado de uma verificação de
capacidades e é separado da configuração. A leitura do ambiente fica em `TargetProfileLoader` (`vigil-bootstrap`).

## Variáveis

| Variável | Obrigatória | Default | Regra |
|---|---|---|---|
| `VIGIL_TARGET_NAME` | sim | — | não pode ser vazia nem em branco |
| `VIGIL_ENVIRONMENT` | sim | — | não pode ser vazia nem em branco |
| `VIGIL_TARGET_HOST` | sim | — | 1 a 253 caracteres `[A-Za-z0-9.-]`; sem esquema, `/`, `@`, `?`, `#`, `;`, espaços ou IPv6 |
| `VIGIL_TARGET_PORT` | não | `5432` | inteiro de 1 a 65535 |
| `VIGIL_TARGET_DATABASE` | sim | — | `[A-Za-z0-9_-]{1,63}` |
| `VIGIL_TARGET_SSL_MODE` | não | `verify-full` | somente `verify-ca` ou `verify-full`, em minúsculas |
| `VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS` | não | `5` | inteiro de 1 a 60; sem fração nem formato ISO-8601 |

Uma variável presente porém em branco é inválida; o default só vale quando a variável está ausente.

## Alvo único

Se existir `VIGIL_TARGETS` ou qualquer variável `VIGIL_TARGET_<dígitos>_*`, o startup falha com código 2 e a
mensagem nomeia a variável encontrada (a primeira em ordem alfabética).

## Decisão de TLS

`TargetSslMode` só representa `verify-ca` e `verify-full`. `disable`, `allow`, `prefer` e `require` não são
representáveis, pois não validam o certificado do servidor. Isso é mais restritivo que o pgjdbc e segue a regra de
segurança do projeto de nunca desabilitar a verificação TLS.

## Segredos

Nenhum segredo entra em `TargetProfile`, em `toString` ou em mensagens de erro (as mensagens citam apenas o nome da
variável e a regra, nunca o valor). A senha futura virá de uma variável separada (ou secret manager) lida
exclusivamente pelo adapter de conexão. O adapter deve montar a URL JDBC apenas com os campos já validados.
