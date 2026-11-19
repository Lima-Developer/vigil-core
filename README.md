# Vigil

> Observabilidade, auditoria e curadoria de consultas PostgreSQL com evidências, validação isolada e decisão humana.

[![Status](https://img.shields.io/badge/status-experimental--snapshot-E9AF08?style=flat-square)](#status-do-projeto)
[![Java](https://img.shields.io/badge/Java-21-0064E0?style=flat-square)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-0D8626?style=flat-square)](LICENSE)

## Estado do projeto

> **⚠️ Experimental — não indicado para ambiente produtivo.**
>
> A versão atual é uma *snapshot* de pesquisa e demonstração. Ela foi construída para avaliar um fluxo seguro de curadoria de consultas em laboratório: coleta de sinais, criação de caso auditável, sanitização, diagnóstico, hipótese, validação isolada e decisão humana. Não oferece disponibilidade, suporte operacional, persistência centralizada, autenticação multiusuário ou garantia de compatibilidade para uso produtivo.
>
> O Vigil **não executa DDL ou DML no banco monitorado**, não envia dados reais ao corpus de laboratório e não permite que modelos de linguagem aprovem ou apliquem mudanças. Leia [Segurança e limites](#segurança-e-limites) antes de conectar qualquer ambiente.

## O problema

Diagnosticar consultas lentas em bancos relacionais exige conciliar telemetria, planos, metadados, hipóteses de índice, equivalência semântica e custo operacional. O Vigil estrutura essa investigação sem transformar uma sugestão automática em alteração de banco.

O sistema reúne:

- seleção de consultas candidatas a partir de telemetria e impacto;
- `AnalysisCase` com estados, eventos e trilha de auditoria persistida em SQLite;
- contexto sanitizado e *fail-closed* para impedir o envio de SQL ou identificadores inseguros;
- diagnóstico determinístico e validações em *sandbox* com dados sintéticos;
- comparação reproduzível de queries complexas, planos e decisões B0–B3;
- porta de inferência desacoplada de fornecedor e adaptador local Ollama, sempre subordinados a contexto aprovado e à curadoria humana;
- interface local para explorar casos, telemetria, auditoria, uso de IA e o roteiro da demonstração.

## Demonstração visual

A interface local inclui um acervo de 10 consultas complexas, seus planos, medições, curadorias e comparativos. Também há um fluxo individual que permite avançar do caso observado até a decisão humana:

```text
Consulta observada
  → triagem justificada
  → evidência sintética e classificação automática baseada em artefato isolado
  → AWAITING_HUMAN
  → aprovar | rejeitar | solicitar alterações
```

No modo `?demo=1`, todos os dados são sintéticos e nenhuma alteração é persistida. No modo local com SQLite, a decisão e os eventos do caso são persistidos. A classificação encadeada só lê o resultado versionado do laboratório físico isolado; não executa DDL/DML no target.

## Início rápido

### Pré-requisitos

- JDK 21;
- Git;
- Docker, apenas para laboratórios e testes de integração que o utilizem;
- PostgreSQL 16.4, opcionalmente, para reproduzir os experimentos de consulta.

### Compilar e testar

```bash
git clone https://github.com/Lima-Developer/vigil-core.git
cd vigil-core
./mvnw verify
```

No Windows, use `mvnw.cmd verify` ou `./mvnw.cmd verify` no PowerShell.

### Executar uma demonstração efêmera

O comando abaixo cria um SQLite temporário e o remove quando a aplicação é encerrada. Não requer credenciais PostgreSQL, rede ou dados reais.

```bash
./mvnw -pl vigil-bootstrap -am spring-boot:run \
  -Dspring-boot.run.arguments="--vigil.demo.ephemeral=true --server.port=8080"
```

Abra [http://127.0.0.1:8080/?demo=1#overview](http://127.0.0.1:8080/?demo=1#overview).

Para a demonstração de caso persistida localmente, consulte o [roteiro de avaliação visual](docs/bootstrap/caso-demonstracao.md). Para uma apresentação sem dependência de rede, use também `?demo=1`.

### Inferência local opcional

O adaptador local usa o contrato HTTP do [Ollama](https://ollama.com/) em `http://127.0.0.1:11434/api/generate`. Ele não é iniciado nem chamado automaticamente: uma funcionalidade deve fornecer um contexto já sanitizado e autorizado. Para configurá-lo, defina `VIGIL_INFERENCE_ENDPOINT`, `vigil.inference.enabled=true` e `vigil.inference.model=<modelo>`; a ausência do serviço local é tratada como falha explícita, sem *fallback* para um provedor externo.

Para avaliar o adaptador contra um runtime real (sem enviar consulta ou dado de negócio), instale e inicie o Ollama, baixe um modelo local e execute:

```bash
ollama pull qwen3:8b
VIGIL_OLLAMA_MODEL=qwen3:8b ./mvnw -pl vigil-adapters \
  -Dtest=OllamaInferenceAdapterLocalTest -Dvigil.ollama.e2e=true test
```

No PowerShell:

```powershell
$env:VIGIL_OLLAMA_MODEL = 'qwen3:8b'
.\mvnw.cmd -pl vigil-adapters -Dtest=OllamaInferenceAdapterLocalTest -Dvigil.ollama.e2e=true test
```

O fluxo de demonstração avalia a hipótese por `ArtifactHypothesisValidationAdapter`, que lê somente `docs/experiments/hypopg-physical-index-2026-11-18/result.json`. Esse artefato exige `EXPLAIN (ANALYZE, BUFFERS)`, índice físico no laboratório e confirmação de que o banco monitorado não foi acessado. O resultado é classificado automaticamente como `VALIDATED`, `INVALIDATED` ou `INCONCLUSIVE` antes de chegar à decisão humana.

## Arquitetura

O Vigil adota monólito modular com arquitetura hexagonal. O domínio não depende de Spring, JDBC, SQLite, Docker ou SDKs de IA.

```text
vigil-domain        regras, estados, eventos e objetos de valor
vigil-application   casos de uso e portas
vigil-adapters       SQLite, PostgreSQL e integrações externas
vigil-bootstrap      composição Spring Boot, CLI e interface web local
```

Principais referências arquiteturais:

- [ADR-001 — observabilidade progressiva](docs/architecture/ADR-001-observabilidade-progressiva-e-baseline-adaptativo.md)
- [ADR-002 — persistência local em SQLite](docs/architecture/ADR-002-sqlite-persistencia-local.md)
- [ADR-003 — arquitetura hexagonal](docs/architecture/ADR-003-monolito-modular-arquitetura-hexagonal.md)
- [Máquina de estados do `AnalysisCase`](docs/domain/maquina-de-estados.md)

## Segurança e limites

O modelo de segurança é deliberadamente conservador:

- a conexão PostgreSQL é de menor privilégio e somente leitura;
- `EXPLAIN ANALYZE` não é executado no target monitorado;
- o Vigil não lê dados de negócio nem executa `CREATE`, `ALTER`, `INSERT`, `UPDATE` ou `DELETE` no target;
- credenciais não são exibidas na interface, na auditoria ou em mensagens públicas;
- contexto não sanitizável bloqueia inferência externa;
- o *sandbox* é descartável e alimentado exclusivamente por dados sintéticos;
- a IA produz um artefato de curadoria; não tem autoridade para aplicar mudanças ou substituir a decisão humana.

Detalhes de acesso e privilégios: [acesso PostgreSQL com menor privilégio](docs/architecture/postgres-acesso-target.md).

### O que ainda não é suportado

Esta *snapshot* não é um produto de monitoramento corporativo. Estão fora do escopo atual, entre outros:

- múltiplos targets, autenticação, controle de acesso e operação multiusuário;
- alta disponibilidade, retenção centralizada e observabilidade operacional de produção;
- execução automática de recomendações, DDL ou DML;
- clonagem fiel de dados de produção;
- suporte a outros SGBDs além de PostgreSQL;
- garantia de economia financeira ou melhora de desempenho em qualquer banco real.

O recorte completo está em [escopo do MVP](docs/product/mvp-scope.md).

## Laboratórios e reprodutibilidade

O repositório preserva artefatos e scripts para repetir os cenários de pesquisa:

| Laboratório | Finalidade | Referência |
| --- | --- | --- |
| Corpus de queries complexas | 10 consultas, dois *seeds*, equivalência e medições | [documentação](docs/experiments/complex-query-corpus/README.md) |
| B0–B3 | Comparar parecer humano, regras, LLM e abordagem híbrida | [artefatos](docs/experiments/complex-query-corpus/) |
| Curadoria por LLM | Protocolos e importação de resultados de Codex, Claude e OpenRouter | [documentação](docs/experiments/llm-curation/README.md) |
| HypoPG | Avaliação hipotética de índice sem alterar o target | [documentação](docs/experiments/hypopg-validation/README.md) |
| Índice físico isolado | `EXPLAIN (ANALYZE, BUFFERS)` antes/depois em PostgreSQL efêmero | [resultado](docs/experiments/hypopg-physical-index-2026-11-18/README.md) |

Os resultados são evidências de laboratório; não devem ser extrapolados como previsão de desempenho de outro ambiente.

## Interface e design

A interface web local foi organizada como um console de auditoria de alta densidade: visão geral, telemetria, casos, auditoria, uso de IA e roteiro. A linguagem visual usa tokens semânticos, contraste e comportamento responsivo inspirados no tema neutro do [Astryx](https://github.com/meta/astryx), sem incorporar dependências React/StyleX ao runtime Java atual.

## Contribuição

Contribuições são bem-vindas. Antes de abrir uma alteração:

1. leia [CONTRIBUTING.md](CONTRIBUTING.md);
2. mantenha as dependências unidirecionais entre os módulos;
3. inclua testes para alterações de domínio, aplicação ou adapters;
4. não inclua credenciais, SQL real, dados de negócio ou artefatos sensíveis;
5. atualize a documentação quando mudar arquitetura, protocolo experimental ou comportamento observável.

Relate vulnerabilidades conforme [SECURITY.md](SECURITY.md). Para questões de arquitetura, comece pelos ADRs em [`docs/architecture`](docs/architecture/).

## Licença

Distribuído sob a [Licença MIT](LICENSE). Copyright © 2026 João Vitor Fernandes Lima.
