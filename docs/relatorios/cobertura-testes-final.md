# Relatório final de cobertura de testes — Vigil

**Revisão publicada na `master`:** `1e418b8292929d10a6fa01f5abbef32a4ae2ba5e`  
**Configuração de cobertura:** JaCoCo `0.8.12` (`b24b589`)

## Validação executada

```bash
./mvnw -s /private/tmp/vigil-maven-central-settings.xml verify
```

Resultado da suíte: **1.155 testes executados**, **0 falhas**, **0 erros** e **4 ignorados**.

## Cobertura global

| Métrica JaCoCo | Cobertos | Cobertura |
|---|---:|---:|
| Instruções | 15.630 / 16.900 | **92,49%** |
| Linhas | 2.752 / 2.976 | **92,47%** |
| Métodos | 567 / 613 | **92,50%** |
| Classes | 174 / 185 | **94,05%** |
| Ramos | 1.471 / 1.748 | 84,15% |
| Complexidade | 1.218 / 1.507 | 80,82% |

A meta de **90% de cobertura geral** foi atingida, tanto por linhas quanto por instruções. Ramos e complexidade são métricas mais rigorosas de caminhos condicionais e são apresentadas separadamente para não induzir interpretação incorreta.

## Cobertura por módulo

| Módulo | Linhas | Instruções | Ramos | Métodos |
|---|---:|---:|---:|---:|
| `vigil-domain` | 97,07% | 95,28% | 81,82% | 95,83% |
| `vigil-application` | 90,33% | 90,88% | 88,68% | 85,37% |
| `vigil-adapters` | 95,53% | 95,47% | 89,08% | 97,32% |
| `vigil-bootstrap` | 82,80% | 84,43% | 67,27% | 85,33% |

## Escopo dos testes adicionados

- Validação de contratos e objetos de valor da camada de aplicação.
- Estados disponíveis, parciais, de aquecimento e de erro na telemetria do *workspace*.
- Coleta contínua de telemetria e preservação de armazenamento efêmero de demonstração.
- Regras e validações de contratos do domínio.

## Relatórios detalhados do JaCoCo

Após executar o comando de validação, os relatórios HTML são gerados em:

- `vigil-domain/target/site/jacoco/index.html`
- `vigil-application/target/site/jacoco/index.html`
- `vigil-adapters/target/site/jacoco/index.html`
- `vigil-bootstrap/target/site/jacoco/index.html`
